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

package com.vmware.admiral.request;

import static com.vmware.admiral.common.util.PropertyUtils.mergeProperty;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption.STORE_ONLY;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.LINK;
import static com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption.SINGLE_ASSIGNMENT;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.AssertUtil;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.container.GroupResourcePolicyService;
import com.vmware.admiral.compute.container.GroupResourcePolicyService.GroupResourcePolicyState;
import com.vmware.admiral.request.ReservationAllocationTaskService.ReservationAllocationTaskState.SubStage;
import com.vmware.admiral.request.allocation.filter.HostSelectionFilter.HostSelection;
import com.vmware.admiral.service.common.AbstractTaskStatefulService;
import com.vmware.admiral.service.common.ServiceTaskCallback.ServiceTaskCallbackResponse;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ResourcePoolService;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;


/**
 * Task implementing the reservation allocation request resource work flow.
 * <p>
 * It is related to ContainerDescription drag & drop functionality.
 * The work flow is the following:
 * <ul>
 * <li>Create resource pool</li>
 * <li>Create GroupResourcePolicy and linked it to created resource pool</li>
 * <li>Find container's host</li>
 * <li>Update resourcePoolLink of container's host</li>
 * </ul>
 * <p>
 */
public class ReservationAllocationTaskService extends
        AbstractTaskStatefulService<ReservationAllocationTaskService.ReservationAllocationTaskState, ReservationAllocationTaskService.ReservationAllocationTaskState.SubStage> {

    public static final String DISPLAY_NAME = "Reservation Allocation";

    public static final String FACTORY_LINK = ManagementUriParts.REQUEST_RESERVATION_ALLOCATION_TASKS;

    public static final String CONTAINER_HOST_ID_CUSTOM_PROPERTY = "__containerHostId";

    public static class ReservationAllocationTaskState extends
            com.vmware.admiral.service.common.TaskServiceDocument<ReservationAllocationTaskState.SubStage> {

        /** Name of the container. */
        @Documentation(description = "Name of the container.")
        @UsageOption(option = PropertyUsageOption.OPTIONAL)
        public String name;

        /** The description that defines the requested resource. */
        @Documentation(description = "The description that defines the requested resource.")
        @PropertyOptions(usage = { SINGLE_ASSIGNMENT, LINK }, indexing = STORE_ONLY)
        public String resourceDescriptionLink;

        /** Type of resource to create. */
        @Documentation(description = "Type of resource to create.")
        @PropertyOptions(usage = SINGLE_ASSIGNMENT, indexing = STORE_ONLY)
        public String resourceType;

        /** Number of resources to provision. */
        @Documentation(description = "Number of resources to provision.")
        @PropertyOptions(usage = { PropertyUsageOption.SINGLE_ASSIGNMENT },
                indexing = { PropertyIndexingOption.STORE_ONLY })
        public long resourceCount;

        /** Set by task. The link to the selected group policy. */
        @Documentation(description = "Set by task. The link to the selected group policy.")
        @PropertyOptions(usage = PropertyUsageOption.SERVICE_USE, indexing = STORE_ONLY)
        public String groupResourcePolicyLink;

        /**
         * Set by task. Selected group policy links and associated resourcePoolLinks. Ordered by priority asc.
         */
        @Documentation(description = "Set by task. Selected group policy links and associated resourcePoolLinks. Ordered by priority asc.")
        @PropertyOptions(usage = PropertyUsageOption.SERVICE_USE, indexing = STORE_ONLY)
        public LinkedHashMap<String, String> resourcePoolsPerGroupPolicyLinks;

        /** (Internal) Set by task after the ComputeState is found to host the containers */
        @Documentation(description = "Set by task after the ComputeState is found to host the containers.")
        @PropertyOptions(usage = PropertyUsageOption.SERVICE_USE, indexing = STORE_ONLY)
        public List<HostSelection> hostSelections;

        /**
         * The overall contextId of this request (could be the same across multiple
         * request - composite allocation)
         */
        @Documentation(description = "The overall contextId of this request (could be the same across multiple request - composite allocation)")
        @PropertyOptions(usage = { PropertyUsageOption.SINGLE_ASSIGNMENT },
                indexing = { PropertyIndexingOption.STORE_ONLY })
        public String contextId;

        @Documentation(description = "The link of the ResourcePoolState associated with this task.")
        @UsageOption(option = PropertyUsageOption.LINK)
        public String resourcePoolLink;

        public static enum SubStage {
            CREATED,
            GROUP_POLICY_CREATED,
            RESOURCE_POOL_ADJUSTMENT,
            COMPLETED,
            ERROR;
        }

    }

    public ReservationAllocationTaskService() {
        super(ReservationAllocationTaskState.class, SubStage.class, DISPLAY_NAME);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    }

    @Override
    protected void validateStateOnStart(ReservationAllocationTaskState state)
            throws IllegalArgumentException {
        AssertUtil.assertNotEmpty(state.resourceDescriptionLink, "resourceDescriptionLink");
        AssertUtil.assertNotNull(state.customProperties, "Custom properties can not be null.");
        AssertUtil.assertNotNull(state.customProperties.get(CONTAINER_HOST_ID_CUSTOM_PROPERTY),
                String.format("%s can not be null.", CONTAINER_HOST_ID_CUSTOM_PROPERTY));
    }

    @Override
    protected void handleStartedStagePatch(ReservationAllocationTaskState state) {
        switch (state.taskSubStage) {
        case CREATED:
            createResourcePool(state);
            break;
        case GROUP_POLICY_CREATED:
            // Resource pool has been created, now create a GroupResourcePolicy.
            createGroupResourcePolicy(state);
            break;
        case RESOURCE_POOL_ADJUSTMENT:
            // Change resource pool of container's host.
            updateContainerHostResourcePool(state, null);
            break;
        case COMPLETED:
            complete(state, SubStage.COMPLETED);
            break;
        case ERROR:
            completeWithError(state, SubStage.ERROR);
            break;
        default:
            break;
        }

    }

    @Override
    protected boolean validateStageTransition(Operation patch,
            ReservationAllocationTaskState patchBody, ReservationAllocationTaskState currentState) {

        currentState.groupResourcePolicyLink = mergeProperty(currentState.groupResourcePolicyLink,
                patchBody.groupResourcePolicyLink);

        currentState.resourcePoolsPerGroupPolicyLinks = mergeProperty(
                currentState.resourcePoolsPerGroupPolicyLinks,
                patchBody.resourcePoolsPerGroupPolicyLinks);

        currentState.resourcePoolLink = mergeProperty(currentState.resourcePoolLink,
                patchBody.resourcePoolLink);
        return false;
    }

    @Override
    protected ServiceTaskCallbackResponse getFinishedCallbackResponse(
            ReservationAllocationTaskState state) {
        CallbackCompleteResponse finishedResponse = new CallbackCompleteResponse();
        finishedResponse.copy(state.serviceTaskCallback.getFinishedResponse());
        finishedResponse.groupResourcePolicyLink = state.groupResourcePolicyLink;
        finishedResponse.resourcePoolsPerGroupPolicyLinks = state.resourcePoolsPerGroupPolicyLinks;
        if (state.groupResourcePolicyLink == null || state.groupResourcePolicyLink.isEmpty()) {
            logWarning("No GroupResourcePolicy found for reservated resources.");
        }
        return finishedResponse;
    }

    protected static class CallbackCompleteResponse extends ServiceTaskCallbackResponse {
        public String groupResourcePolicyLink;
        public LinkedHashMap<String, String> resourcePoolsPerGroupPolicyLinks;
    }

    private void createResourcePool(ReservationAllocationTaskState state) {

        ResourcePoolState poolState = new ResourcePoolState();
        poolState.id = state.name + "-" + UUID.randomUUID().toString();
        poolState.name = poolState.id;
        poolState.documentSelfLink = poolState.id;
        poolState.tenantLinks = state.tenantLinks;

        sendRequest(Operation
                .createPost(this, ResourcePoolService.FACTORY_LINK)
                .setBody(poolState)
                .setCompletion(
                        (o, e) -> {
                            if (e != null) {
                                failTask(
                                        String.format("Failed to create resource pool. Error: %s",
                                                Utils.toString(e)),
                                        e);
                                return;
                            }

                            ResourcePoolState resourcePool = o.getBody(ResourcePoolState.class);
                            state.resourcePoolLink = resourcePool.documentSelfLink;

                            ReservationAllocationTaskState body = createUpdateSubStageTask(state,
                                    ReservationAllocationTaskState.SubStage.GROUP_POLICY_CREATED);
                            body.resourcePoolLink = resourcePool.documentSelfLink;
                            sendSelfPatch(body);

                        }));
    }

    private void createGroupResourcePolicy(ReservationAllocationTaskState state) {

        GroupResourcePolicyState policyState = new GroupResourcePolicyState();
        policyState.name = state.name + "-" + UUID.randomUUID().toString();
        policyState.documentSelfLink = policyState.name;
        policyState.tenantLinks = state.tenantLinks;
        policyState.maxNumberInstances = state.resourceCount;
        policyState.memoryLimit = 0;
        policyState.storageLimit = 0;
        policyState.resourcePoolLink = state.resourcePoolLink;

        sendRequest(Operation
                .createPost(this, GroupResourcePolicyService.FACTORY_LINK)
                .setBody(policyState)
                .setCompletion(
                        (o, e) -> {
                            if (e != null) {
                                failTask(
                                        String.format(
                                                "Failed to create GroupResourcePolicyState. Error: %s",
                                                Utils.toString(e)),
                                        e);
                                return;
                            }

                            GroupResourcePolicyState groupResourcePolicy = o
                                    .getBody(GroupResourcePolicyState.class);

                            ReservationAllocationTaskState body = createUpdateSubStageTask(state,
                                    ReservationAllocationTaskState.SubStage.RESOURCE_POOL_ADJUSTMENT);

                            body.groupResourcePolicyLink = groupResourcePolicy.documentSelfLink;
                            body.resourcePoolsPerGroupPolicyLinks = new LinkedHashMap<>();
                            body.resourcePoolsPerGroupPolicyLinks.put(groupResourcePolicy.documentSelfLink, groupResourcePolicy.resourcePoolLink);
                            sendSelfPatch(body);

                        }));

    }

    private void updateContainerHostResourcePool(ReservationAllocationTaskState state,
            ComputeState containerHost) {

        // Get container's host based on its id property.
        if (containerHost == null) {
            retrieveContainerHostById(state,
                    (host) -> this.updateContainerHostResourcePool(state, host));
            return;
        }

        containerHost.resourcePoolLink = state.resourcePoolLink;

        sendRequest(Operation
                .createPost(this, ComputeService.FACTORY_LINK)
                .setBody(containerHost)
                .setCompletion(
                        (o, e) -> {
                            if (e != null) {
                                failTask(String.format(
                                        "Failed to change resource pool of host with id: %s. Error: %s",
                                        containerHost.id, e.getMessage()), e);
                                return;
                            }

                            ReservationAllocationTaskState body = createUpdateSubStageTask(state,
                                    ReservationAllocationTaskState.SubStage.COMPLETED);

                            body.groupResourcePolicyLink = state.groupResourcePolicyLink;
                            sendSelfPatch(body);

                        }));

    }

    // Get host which has id equals to ContainerDescription custom property's value
    // "containerHostId".
    private void retrieveContainerHostById(ReservationAllocationTaskState state,
            Consumer<ComputeState> callbackFunction) {

        final ComputeState[] result = new ComputeState[1];

        final QueryTask queryTask = QueryUtil.buildPropertyQuery(ComputeState.class,
                ComputeState.FIELD_NAME_ID,
                state.getCustomProperty(CONTAINER_HOST_ID_CUSTOM_PROPERTY));

        QueryUtil.addExpandOption(queryTask);

        new ServiceDocumentQuery<ComputeState>(getHost(), ComputeState.class)
                .query(queryTask, (r) -> {
                    if (r.hasException()) {
                        failTask(String.format(
                                "Exception during retrieving a host with id [%s]. Error: [%s]",
                                state.getCustomProperty(CONTAINER_HOST_ID_CUSTOM_PROPERTY),
                                Utils.toString(r.getException())), r.getException());
                    } else if (r.hasResult()) {
                        result[0] = r.getResult();
                    } else {
                        if (result[0] == null) {
                            failTask(
                                    String.format("Container's host with id: [%s] not found!",
                                            state.getCustomProperty(
                                                    CONTAINER_HOST_ID_CUSTOM_PROPERTY)),
                                    r.getException());
                            return;
                        }

                        callbackFunction.accept(result[0]);
                    }
                });

    }

}
