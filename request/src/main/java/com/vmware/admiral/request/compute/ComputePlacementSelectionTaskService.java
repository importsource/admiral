/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.request.compute;

import static com.vmware.admiral.common.util.AssertUtil.assertNotEmpty;
import static com.vmware.admiral.common.util.PropertyUtils.mergeProperty;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption.STORE_ONLY;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.LINK;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.LINKS;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.SERVICE_USE;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.SINGLE_ASSIGNMENT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.ComputeConstants;
import com.vmware.admiral.compute.endpoint.EndpointService.EndpointState;
import com.vmware.admiral.service.common.AbstractTaskStatefulService;
import com.vmware.admiral.service.common.DefaultSubStage;
import com.vmware.admiral.service.common.ServiceTaskCallback.ServiceTaskCallbackResponse;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription.ComputeType;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.PowerState;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationSequence;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;
import com.vmware.xenon.services.common.QueryTask.Query;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification.QueryOption;

/**
 * Task implementing compute placement selection for provisioning a given number of instances of a
 * {@link ComputeDescription}.
 */
public class ComputePlacementSelectionTaskService extends
        AbstractTaskStatefulService<ComputePlacementSelectionTaskService.ComputePlacementSelectionTaskState, DefaultSubStage> {

    public static final String FACTORY_LINK = ManagementUriParts.REQUEST_PROVISION_COMPUTE_PLACEMENT_TASKS;
    public static final String DISPLAY_NAME = "Compute Placement Selection";
    private static final int QUERY_RETRY_COUNT = Integer.getInteger(
            "com.vmware.admiral.service.placement.query.retries", 2);

    // cached description of the requested compute resource
    private volatile ComputeDescription computeDescription;

    /**
     * Task parameters and internal state.
     */
    public static class ComputePlacementSelectionTaskState
            extends com.vmware.admiral.service.common.TaskServiceDocument<DefaultSubStage> {

        @Documentation(description = "The description that defines the requested resource.")
        @PropertyOptions(usage = { SINGLE_ASSIGNMENT, LINK }, indexing = STORE_ONLY)
        public String computeDescriptionLink;

        @Documentation(description = "Number of resources to provision.")
        @PropertyOptions(usage = SINGLE_ASSIGNMENT, indexing = STORE_ONLY)
        public long resourceCount;

        @Documentation(description = "The resource pool to be used for this placement.")
        @PropertyOptions(usage = { SINGLE_ASSIGNMENT, LINK }, indexing = STORE_ONLY)
        public String resourcePoolLink;

        @Documentation(description = "Set by the task as result of the selection algorithm filters."
                + " The number of selected computes matches the given resourceCount.")
        @PropertyOptions(usage = { SERVICE_USE, LINKS }, indexing = STORE_ONLY)
        public Collection<String> selectedComputePlacementLinks;
    }

    /**
     * Task output.
     */
    protected static class CallbackCompleteResponse extends ServiceTaskCallbackResponse {
        /**
         * Set by the task as result of the selection algorithm filters. The number of selected
         * computes matches the given {@code resourceCount}.
         */
        Collection<String> selectedComputePlacementLinks;
    }

    /**
     * Constructs a new instance.
     */
    public ComputePlacementSelectionTaskService() {
        super(ComputePlacementSelectionTaskState.class, DefaultSubStage.class, DISPLAY_NAME);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    }

    @Override
    protected void handleStartedStagePatch(ComputePlacementSelectionTaskState state) {
        switch (state.taskSubStage) {
        case CREATED:
            selectPlacement(state, computeDescription, QUERY_RETRY_COUNT);
            break;
        case COMPLETED:
            complete(state, DefaultSubStage.COMPLETED);
            break;
        case ERROR:
            completeWithError(state, DefaultSubStage.ERROR);
            break;
        default:
            break;
        }
    }

    @Override
    protected boolean validateStageTransition(Operation patch,
            ComputePlacementSelectionTaskState patchBody,
            ComputePlacementSelectionTaskState currentState) {
        currentState.selectedComputePlacementLinks = mergeProperty(
                currentState.selectedComputePlacementLinks,
                patchBody.selectedComputePlacementLinks);

        return false;
    }

    @Override
    protected void validateStateOnStart(ComputePlacementSelectionTaskState state) {
        assertNotEmpty(state.computeDescriptionLink, "computeDescriptionLink");
        assertNotEmpty(state.resourcePoolLink, "resourcePoolLink");
        if (state.resourceCount < 1) {
            throw new IllegalArgumentException("'resourceCount' must be greater than 0.");
        }
    }

    @Override
    protected ServiceTaskCallbackResponse getFinishedCallbackResponse(
            ComputePlacementSelectionTaskState state) {
        CallbackCompleteResponse finishedResponse = new CallbackCompleteResponse();
        finishedResponse.copy(state.serviceTaskCallback.getFinishedResponse());
        finishedResponse.selectedComputePlacementLinks = state.selectedComputePlacementLinks;
        return finishedResponse;
    }

    private void selectPlacement(ComputePlacementSelectionTaskState state,
            ComputeDescription desc, int maxRetries) {
        if (desc == null) {
            getComputeDescription(state,
                    (description) -> this.selectPlacement(state, description, maxRetries));
            return;
        }

        Query.Builder queryBuilder = Query.Builder.create()
                .addKindFieldClause(ComputeDescription.class)
                .addCollectionItemClause(
                        ComputeDescription.FIELD_NAME_SUPPORTED_CHILDREN,
                        ComputeType.VM_GUEST.toString());

        // TODO pmitrov: consider removing zoneId condition in favor of parent checks
        // (to be done when this class is merged w/ PlacementHostSelectionTaskService)
        if (desc.zoneId != null && !desc.zoneId.isEmpty()) {
            queryBuilder.addFieldClause(ComputeDescription.FIELD_NAME_ZONE_ID, desc.zoneId);
        }

        QueryTask queryTask = QueryTask.Builder.create().setQuery(queryBuilder.build()).build();

        final List<String> computeDescriptionLinks = new ArrayList<>();
        ServiceDocumentQuery<ComputeDescription> query = new ServiceDocumentQuery<ComputeDescription>(
                getHost(), ComputeDescription.class);
        query.query(queryTask, (r) -> {
            if (r.hasException()) {
                failTask("Error querying for placement compute description.", r.getException());
            } else if (r.hasResult()) {
                computeDescriptionLinks.add(r.getDocumentSelfLink());
            } else {
                if (computeDescriptionLinks.isEmpty()) {
                    failTask(null, new IllegalStateException(
                            "No ComputeDescription found for compute placement"));
                    return;
                }
                proceedComputeSelection(state, desc, computeDescriptionLinks, maxRetries);
            }
        });
    }

    private void proceedComputeSelection(ComputePlacementSelectionTaskState state,
            ComputeDescription desc,
            Collection<String> computeDescriptionLinks, int maxRetries) {
        // TODO pmitrov: looking for a powered-on VM_GUEST compute container is not going to work
        // for AWS since there is a single endpoint-level ComputeState in this case
        // with UNKNOWN power state; we may add support for this case too

        Query.Builder queryBuilder = Query.Builder.create()
                .addKindFieldClause(ComputeState.class)
                .addInClause(ComputeState.FIELD_NAME_DESCRIPTION_LINK, computeDescriptionLinks)
                .addFieldClause(ComputeState.FIELD_NAME_RESOURCE_POOL_LINK, state.resourcePoolLink)
                .addFieldClause(ComputeState.FIELD_NAME_POWER_STATE, PowerState.ON.toString());
        QueryTask queryTask = QueryTask.Builder.create()
                .setQuery(queryBuilder.build())
                .addOption(QueryOption.EXPAND_CONTENT)
                .build();

        ServiceDocumentQuery<ComputeState> queryHelper = new ServiceDocumentQuery<ComputeState>(
                getHost(), ComputeState.class);
        List<ComputeState> computeStates = new ArrayList<>();
        queryHelper.query(
                queryTask,
                (r) -> {
                    if (r.hasException()) {
                        failTask("Error querying for compute placements.", r.getException());
                    } else if (r.hasResult()) {
                        computeStates.add(r.getResult());
                    } else {
                        if (computeStates.isEmpty()) {
                            if (maxRetries > 0) {
                                getHost().log(Level.FINE,
                                        "Compute placement query retries left " + (maxRetries - 1));
                                getHost().schedule(
                                        () -> selectPlacement(state, desc, maxRetries - 1),
                                        QueryUtil.QUERY_RETRY_INTERVAL_MILLIS,
                                        TimeUnit.MILLISECONDS);
                            } else {
                                selectByEndpointAsCompute(state, desc, computeDescriptionLinks,
                                        null);
                            }
                            return;
                        }

                        selection(state, computeStates);
                    }
                });
    }

    private void selectByEndpointAsCompute(ComputePlacementSelectionTaskState state,
            ComputeDescription desc, Collection<String> computeDescriptionLinks,
            ComputeState endpointCompute) {
        if (endpointCompute == null) {
            getEndpointCompute(state, (epc) -> selectByEndpointAsCompute(state, desc,
                    computeDescriptionLinks, epc));
            return;
        }

        if (computeDescriptionLinks.contains(endpointCompute.descriptionLink)) {
            selection(state, Arrays.asList(endpointCompute));
        } else {
            failTask(null, new IllegalStateException(
                    "No powered-on compute placement candidates found in "
                            + "resource pool " + state.resourcePoolLink));
        }
    }

    private void selection(final ComputePlacementSelectionTaskState state,
            final List<ComputeState> availableComputeStates) {
        if (availableComputeStates.isEmpty()) {
            failTask("No compute placements found", null);
            return;
        }
        ArrayList<ComputeState> selectedComputeStates = new ArrayList<>(availableComputeStates);
        Collections.shuffle(selectedComputeStates);

        int initialSize = selectedComputeStates.size();
        int diff = (int) (state.resourceCount - initialSize);
        if (diff > 0) {
            /*
             * Cycle the list of compute selections until we reach resourceCount number of entries,
             * i.e. if we have 3 computes [A, B, C] and resourceCount is 7 we will have [A, B, C, A,
             * B, C, A]
             */
            for (int i = 0; i < diff / initialSize; ++i) {
                selectedComputeStates.addAll(selectedComputeStates.subList(0, initialSize));
            }

            selectedComputeStates.addAll(selectedComputeStates.subList(0, diff % initialSize));
        }

        ComputePlacementSelectionTaskState newState = createUpdateSubStageTask(state,
                DefaultSubStage.COMPLETED);
        newState.selectedComputePlacementLinks = selectedComputeStates.stream()
                .map(cr -> cr.documentSelfLink).collect(Collectors.toList());
        sendSelfPatch(newState);
    }

    private void getComputeDescription(ComputePlacementSelectionTaskState state,
            Consumer<ComputeDescription> callbackFunction) {
        if (computeDescription != null) {
            callbackFunction.accept(computeDescription);
            return;
        }
        sendRequest(Operation.createGet(this, state.computeDescriptionLink)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Failure retrieving compute description "
                                + state.computeDescriptionLink, e);
                        return;
                    }

                    ComputeDescription desc = o.getBody(ComputeDescription.class);
                    this.computeDescription = desc;
                    callbackFunction.accept(desc);
                }));
    }

    private void getEndpointCompute(ComputePlacementSelectionTaskState state,
            Consumer<ComputeState> callbackFunction) {

        Operation poolOp = Operation.createGet(this, state.resourcePoolLink);
        Operation endpointOp = Operation.createGet(null);
        Operation computeOp = Operation.createGet(null);
        OperationSequence.create(poolOp)
                .setCompletion((ops, exs) -> {
                    if (exs != null) {
                        failTask("Failure retrieving ResourcePool: " + Utils.toString(exs),
                                null);
                        return;
                    }
                    ResourcePoolState pool = ops.get(poolOp.getId())
                            .getBody(ResourcePoolState.class);
                    if (pool.customProperties == null || !pool.customProperties
                            .containsKey(ComputeConstants.ENDPOINT_LINK_PROP_NAME)) {
                        failTask("ResourcePool:" + state.resourcePoolLink
                                + ", is not associated with an Endpoint", null);
                        return;
                    }
                    String endpointLink = pool.customProperties
                            .get(ComputeConstants.ENDPOINT_LINK_PROP_NAME);
                    endpointOp.setUri(UriUtils.buildUri(getHost(), endpointLink));
                })
                .next(endpointOp)
                .setCompletion((ops, exs) -> {
                    if (exs != null) {
                        failTask("Failure retrieving Endpoint: " + Utils.toString(exs),
                                null);
                        return;
                    }
                    EndpointState endpoint = ops.get(endpointOp.getId())
                            .getBody(EndpointState.class);

                    computeOp.setUri(UriUtils.buildUri(getHost(), endpoint.computeLink));
                })
                .next(computeOp)
                .setCompletion((ops, exs) -> {
                    if (exs != null) {
                        failTask("Failure retrieving ComputeState: " + Utils.toString(exs),
                                null);
                        return;
                    }
                    ComputeState compute = ops.get(computeOp.getId()).getBody(ComputeState.class);

                    callbackFunction.accept(compute);
                })
                .sendWith(this);
    }
}
