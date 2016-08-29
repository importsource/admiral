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

package com.vmware.admiral.service.common;

import static com.vmware.admiral.common.util.AssertUtil.assertNotNull;
import static com.vmware.admiral.common.util.PropertyUtils.mergeCustomProperties;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServerX509TrustManager;
import com.vmware.admiral.common.util.ServiceClientFactory;
import com.vmware.admiral.common.util.ServiceDocumentTemplateUtil;
import com.vmware.admiral.common.util.ServiceUtils;
import com.vmware.admiral.service.common.CounterSubTaskService.CounterSubTaskState;
import com.vmware.admiral.service.common.ServiceTaskCallback.ServiceTaskCallbackResponse;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceClient;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyDescription;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyIndexingOption;
import com.vmware.xenon.common.ServiceDocumentDescription.PropertyUsageOption;
import com.vmware.xenon.common.ServiceErrorResponse;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public abstract class AbstractTaskStatefulService<T extends TaskServiceDocument<E>, E extends Enum<E>>
        extends StatefulService {

    private static final int RETRIES_COUNT = Integer.getInteger(
            "com.vmware.admiral.service.tasks.retries", 3);
    protected static final long COMPLETION_POLLING_PERIOD_MILLIS = Long
            .getLong(
                    "com.vmware.admiral.service.common.AbstractTaskStatefulService.completion.polling.period.millis",
                    TimeUnit.SECONDS.toMillis(3));

    private static volatile ServiceClient serviceClient;

    protected Class<E> subStageType;

    private final String displayName;

    /** SubStages that are indicating a transient state and order of patching can't be guaranteed */
    protected Set<E> transientSubStages = Collections.emptySet();

    public static class TaskStatusState extends MultiTenantDocument {
        public static final String FIELD_NAME_EVENT_LOG_LINK = "eventLogLink";
        public static final String FIELD_NAME_TASK_INFO = "taskInfo";

        /** The name of the TaskService */
        public String phase;

        /** TaskInfo state of the current TaskService */
        public TaskState taskInfo;

        /** Substage of the current TaskService */
        public String subStage;

        /** progress of the task (0-100%) - should only reported by leaf tasks, otherwise null */
        public Integer progress;

        /** Name of a given task status */
        public String name;

        /** Available when task is marked failed. Link to the corresponding event log.  */
        public String eventLogLink;

        /** List of resource links provisioned or performed operation on them. */
        public List<String> resourceLinks;
    }

    public AbstractTaskStatefulService(Class<? extends TaskServiceDocument<E>> stateType,
            Class<E> subStageType, String displayName) {
        super(stateType);
        this.subStageType = subStageType;
        this.displayName = displayName;
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        TaskServiceDocument<?> template = (TaskServiceDocument<?>) super.getDocumentTemplate();
        template.serviceTaskCallback = ServiceTaskCallback.create("request/requestId string");
        template.taskInfo = new TaskState();
        template.taskInfo.stage = TaskStage.CREATED;
        template.customProperties = new HashMap<String, String>(1);
        template.customProperties.put("propKey string", "customPropertyValue string");

        // service callback:
        PropertyDescription pd = template.documentDescription.propertyDescriptions.get(
                TaskServiceDocument.FIELD_NAME_SERVICE_CALLBACK);
        pd.usageOptions = EnumSet.of(PropertyUsageOption.SINGLE_ASSIGNMENT);
        pd.indexingOptions = EnumSet.of(PropertyIndexingOption.STORE_ONLY);

        // custom properties
        pd = template.documentDescription.propertyDescriptions.get(
                ServiceDocumentTemplateUtil.FIELD_NAME_CUSTOM_PROPERTIES);
        pd.indexingOptions = EnumSet.of(PropertyIndexingOption.STORE_ONLY);

        // sorting
        template.documentDescription.propertyDescriptions.get(
                ServiceDocument.FIELD_NAME_UPDATE_TIME_MICROS).indexingOptions.add(
                PropertyIndexingOption.SORT);

        return template;
    }

    protected void setDocumentTemplateUsageOptions(ServiceDocument template,
            EnumSet<PropertyUsageOption> usageOptions, String... fieldNames) {
        PropertyDescription pd;
        for (String fieldName : fieldNames) {
            pd = template.documentDescription.propertyDescriptions.get(fieldName);
            assertNotNull(pd, fieldName);
            pd.usageOptions = usageOptions;
        }
    }

    protected void setDocumentTemplateIndexingOptions(ServiceDocument template,
            EnumSet<PropertyIndexingOption> indexingOptions, String... fieldNames) {
        PropertyDescription pd;
        for (String fieldName : fieldNames) {
            pd = template.documentDescription.propertyDescriptions.get(fieldName);
            assertNotNull(pd, fieldName);
            pd.indexingOptions = indexingOptions;
        }
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        T state = getBody(post);

        boolean completed = validateNewState(state, post);
        //in exceptional cases the validation could complete the operation.
        if (!completed) {
            post.complete();
        }
    }

    @Override
    public void handleStart(Operation startPost) {
        T state = getBody(startPost);
        if (state.taskInfo == null) {
            startPost.fail(new IllegalStateException("taskInfo must not be null"));
            return;
        }

        if (state.taskSubStage == null) {
            startPost.fail(new IllegalStateException("taskSubStage must not be null"));
            return;
        }

        startPost.complete();

        if (state.taskInfo.stage.ordinal() >= TaskStage.FINISHED.ordinal()) {
            return; // the task should not restart in this stage
        }

        if (state.taskInfo.stage == TaskStage.CREATED || state.documentVersion == 0) {
            state.taskInfo.stage = TaskStage.STARTED;
            logInfo("starting task with parent link: %s", state.serviceTaskCallback.serviceSelfLink);
        } else {
            logWarning("restarting task with parent link: %s",
                    state.serviceTaskCallback.serviceSelfLink);
        }

        handleStagePatch(state);
    }

    private boolean validateNewState(T state, Operation startPost) {
        if (state.documentVersion > 0) {
            return false;
        }
        if (state.serviceTaskCallback == null) {
            state.serviceTaskCallback = ServiceTaskCallback.createEmpty();
        }

        if (state.taskInfo == null) {
            state.taskInfo = new TaskState();
            state.taskInfo.stage = TaskStage.CREATED;
        }

        if (state.taskSubStage == null) {
            state.taskSubStage = Enum.valueOf(subStageType, DefaultSubStage.CREATED.name());
        }

        if (state.documentExpirationTimeMicros == 0) {
            state.documentExpirationTimeMicros = ServiceUtils
                    .getDefaultTaskExpirationTimeInMicros();
        }

        return validateStateOnStart(state, startPost);
    }

    protected boolean validateStateOnStart(T state, Operation startPost)
            throws IllegalArgumentException {
        validateStateOnStart(state);
        return false;
    }

    protected abstract void validateStateOnStart(T state) throws IllegalArgumentException;

    @Override
    public void handlePatch(Operation patch) {
        if (!checkForBody(patch)) {
            return;
        }
        T patchBody = getBody(patch);
        T state = getState(patch);

        // validates AND transitions the stage to the next state by using the patchBody.
        if (validateStageTransitionAndState(patch, patchBody, state)) {
            return;
        }

        updateRequestTracker(state);

        patch.complete();

        handleStagePatch(state);
    }

    protected void updateRequestTracker(T state) {
        updateRequestTracker(state, RETRIES_COUNT);
    }

    protected void updateRequestTracker(T state, int retryCount) {
        if (state != null && state.requestTrackerLink != null) {
            sendRequest(Operation
                    .createPatch(this, state.requestTrackerLink)
                    .setBody(fromTask(state))
                    .setCompletion((o, ex) -> {
                        if (ex != null) {
                            // log but don't fail the task
                            if (ex instanceof CancellationException) {
                                logFine("CancellationException: Failed to update request tracker: %s",
                                        state.requestTrackerLink);
                                // retry only the finished and failed updates. The others are not so
                                // important
                            } else if (TaskStage.FINISHED.name()
                                    .equals(state.taskInfo.stage.name())
                                    || TaskStage.FAILED.name().equals(state.taskInfo.stage.name())
                                    && retryCount > 0) {
                                getHost().schedule(() ->
                                        updateRequestTracker(state, retryCount - 1),
                                        QueryUtil.QUERY_RETRY_INTERVAL_MILLIS,
                                        TimeUnit.MILLISECONDS);
                            } else {
                                logWarning("Failed to update request tracker: %s. Error: %s",
                                        state.requestTrackerLink, Utils.toString(ex));
                            }
                        }
                    }));

        } else if (state != null && state.documentSelfLink != null) {
            logFine("Task doesn't have a requestTrackerLink set: %s ", state.documentSelfLink);
        }
    }

    protected void handleStagePatch(T state) {
        switch (state.taskInfo.stage) {
        case CREATED:
        case STARTED:
            handleStartedStagePatch(state);
            break;
        case FAILED:
            handleFailedStagePatch(state);
            break;
        case FINISHED:
            handleFinishedStagePatch(state);
            break;
        case CANCELLED:
            break;
        default:
            break;
        }
    }

    protected abstract void handleStartedStagePatch(T state);

    protected void handleFailedStagePatch(T state) {
        ServiceErrorResponse err = state.taskInfo.failure;
        logWarning("Task failed with: %s", err == null ? "n.a." : err.message);
        if (err != null && err.stackTrace != null) {
            logFine("Task failure stack trace: %s", err.stackTrace);
        }
        notifyCallerService(state);
    }

    protected void handleFinishedStagePatch(T state) {
        notifyCallerService(state);
    }

    protected boolean validateStageTransitionAndState(Operation patch,
            T patchBody, T currentState) {

        if (patchBody.taskInfo == null || patchBody.taskInfo.stage == null) {
            patch.fail(new IllegalArgumentException("taskInfo and taskInfo.stage are required"));
            return true;
        }

        if (TaskStage.FAILED == patchBody.taskInfo.stage
                && TaskStage.FAILED == currentState.taskInfo.stage) {
            logWarning("Task patched to failed when already in failed state.");
            patch.complete();
            return true;
        }

        if (currentState.taskInfo.stage.ordinal() > patchBody.taskInfo.stage.ordinal()) {
            if (patchBody.taskSubStage == currentState.taskSubStage
                    && DefaultSubStage.ERROR.name().equals(currentState.taskSubStage.name())
                    && TaskStage.FAILED == currentState.taskInfo.stage) {
                patch.complete(); // already failed. No need for another exception.
                return true;
            }
            logWarning("Can't move from %s(%s) to %s(%s). Caller: [%s]",
                    currentState.taskInfo.stage, currentState.taskSubStage,
                    patchBody.taskInfo.stage, patchBody.taskSubStage, patch.getReferer());
            if (TaskStage.FAILED == currentState.taskInfo.stage) {
                patch.complete(); // already failed. No need for another exception.
            } else {
                patch.fail(new IllegalArgumentException("stage can not move backwards from: "
                        + currentState.taskInfo.stage + " to: " + patchBody.taskInfo.stage));
            }
            return true;
        }

        if (patchBody.taskSubStage != null
                && currentState.taskSubStage.ordinal() > patchBody.taskSubStage.ordinal()) {
            if (patchBody.taskInfo.stage == currentState.taskInfo.stage &&
                    !transientSubStages.contains(patchBody.taskSubStage)) {
                logWarning("Can't move from %s(%s) to %s(%s). Caller: [%s]",
                        currentState.taskInfo.stage, currentState.taskSubStage,
                        patchBody.taskInfo.stage, patchBody.taskSubStage, patch.getReferer());
                patch.fail(new IllegalArgumentException("subStage can not move backwards from:"
                        + currentState.taskSubStage + " to: " + patchBody.taskSubStage));
            } else {
                patch.complete();// different stages
            }
            return true;
        }

        logInfo("Moving from %s(%s) to %s(%s). Caller: [%s]",
                currentState.taskInfo.stage, currentState.taskSubStage,
                patchBody.taskInfo.stage, patchBody.taskSubStage, patch.getReferer());

        if (patchBody.taskInfo.failure != null) {
            currentState.taskInfo.failure = patchBody.taskInfo.failure;
            currentState.taskInfo.stage = TaskStage.FAILED;
        }

        // update current stage to new stage
        currentState.taskInfo.stage = patchBody.taskInfo.stage;

        if (patchBody.taskSubStage != null) {
            currentState.taskSubStage = patchBody.taskSubStage;
        }

        currentState.customProperties = mergeCustomProperties(currentState.customProperties,
                patchBody.customProperties);

        adjustStat(patchBody.taskInfo.stage.toString(), 1);
        return validateStageTransition(patch, patchBody, currentState);
    }

    protected abstract boolean validateStageTransition(Operation patch, T patchBody, T currentState);

    protected void createCounterSubTask(T state, long count,
            Consumer<String> callbackFunction) {
        createCounterSubTask(state, count, DefaultSubStage.COMPLETED, callbackFunction);
    }

    protected void createCounterSubTask(T state, long count, Enum<?> substageComplete,
            Consumer<String> callbackFunction) {
        CounterSubTaskState subTaskInitState = new CounterSubTaskState();
        subTaskInitState.completionsRemaining = count;
        subTaskInitState.serviceTaskCallback = ServiceTaskCallback.create(
                state.documentSelfLink, TaskStage.STARTED, substageComplete,
                TaskStage.STARTED, DefaultSubStage.ERROR);

        CounterSubTaskService.createSubTask(this, subTaskInitState, callbackFunction);
    }

    protected void createCounterSubTaskCallback(T state, long count, boolean external,
            Consumer<ServiceTaskCallback> callbackFunction) {
        createCounterSubTaskCallback(state, count, external, DefaultSubStage.COMPLETED,
                callbackFunction);
    }

    protected void createCounterSubTaskCallback(T state, long count, boolean external,
            Enum<?> substageComplete, Consumer<ServiceTaskCallback> callbackFunction) {
        createCounterSubTaskCallback(state, count, external, false, substageComplete,
                callbackFunction);
    }

    protected void createCounterSubTaskCallback(T state, long count, boolean external,
            boolean useCounterService, Enum<?> substageComplete,
            Consumer<ServiceTaskCallback> callbackFunction) {
        if (count == 1 && !useCounterService) {
            ServiceTaskCallback taksCallback = ServiceTaskCallback.create(
                    external ? UriUtils.buildUri(getHost(), state.documentSelfLink).toString()
                            : state.documentSelfLink,
                    TaskStage.STARTED, substageComplete,
                    TaskStage.STARTED, DefaultSubStage.ERROR);
            callbackFunction.accept(taksCallback);
            return;
        }

        createCounterSubTask(state, count, substageComplete, (link) -> {
            ServiceTaskCallback taksCallback = ServiceTaskCallback.create(
                    external ? UriUtils.buildUri(getHost(), link).toString() : link,
                    TaskStage.FINISHED, TaskStage.FAILED);
            callbackFunction.accept(taksCallback);
        });
    }

    protected void completeSubTasksCounter(ServiceTaskCallback taskCallback, Throwable ex) {
        ServiceTaskCallbackResponse response;
        if (ex == null) {
            response = taskCallback.getFinishedResponse();
        } else {
            response = taskCallback.getFailedResponse(ex);
        }
        URI uri;
        if (taskCallback.isExternal()) {
            uri = URI.create(taskCallback.serviceSelfLink);
        } else {
            uri = UriUtils.buildUri(getHost(), taskCallback.serviceSelfLink);
        }

        sendRequest(Operation.createPatch(uri)
                .setBody(response)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        failTask("Notifying counting task failed: %s", e);
                    }
                }));
    }

    protected void completeWithError(T state, E errorSubStage) {
        if (!isFailedOrCancelledTask(state)) {
            state.taskInfo.stage = TaskStage.FAILED;
            state.taskSubStage = errorSubStage;
            sendSelfPatch(state);
        }
    }

    protected void complete(T state, E completeSubStage) {
        if (!isFailedOrCancelledTask(state)) {
            state.taskInfo.stage = TaskStage.FINISHED;
            state.taskSubStage = completeSubStage;
            sendSelfPatch(state);
        }
    }

    protected void sendSelfPatch(T body) {
        sendRequest(Operation.createPatch(getUri())
                .setBody(body)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        logWarning("Self patch failed: %s", Utils.toString(ex));
                        return;
                    }
                }));
    }

    protected void sendSelfDelete() {
        sendRequest(Operation.createDelete(getUri()).setBody(new ServiceDocument()));
    }

    protected boolean isFailedOrCancelledTask(T state) {
        return state.taskInfo != null &&
                (TaskStage.FAILED == state.taskInfo.stage ||
                TaskStage.CANCELLED == state.taskInfo.stage);
    }

    @Override
    public void handleDelete(Operation delete) {
        T state = getState(delete);
        if (state == null || state.taskInfo == null || state.taskInfo.stage == null) {
            delete.complete();
            return;
        }
        switch (state.taskInfo.stage) {
        case CREATED:
        case STARTED:
            String errMsg = String.format("Task expired in stage: %s", state.taskInfo.stage);
            logWarning(errMsg);
            if (!state.serviceTaskCallback.isEmpty()) {
                sendRequest(Operation
                        .createPatch(this, state.serviceTaskCallback.serviceSelfLink)
                        .setBody(state.serviceTaskCallback
                                .getFailedResponse(new IllegalStateException(errMsg)))
                        .addPragmaDirective(
                                Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY)
                        .setCompletion((o, ex) -> {
                            if (ex != null) {
                                logWarning("Updating state to expired failed: %s",
                                        Utils.toString(ex));
                            }
                        }));
            }
            delete.complete();
            break;
        default:
            delete.complete();
            break;
        }
    }

    public void failTask(String errMsg, Throwable t) {
        if (errMsg == null) {
            errMsg = "Unexpected State";
        }
        if (t != null) {
            logWarning("%s%s Error: %s", errMsg, errMsg.endsWith(".") ? "" : ".", Utils.toString(t));
        } else {
            logWarning(errMsg);
        }
        ServiceTaskCallbackResponse body = new ServiceTaskCallbackResponse();
        body.taskInfo = new TaskState();
        body.taskInfo.stage = TaskStage.FAILED;
        body.taskSubStage = DefaultSubStage.ERROR;
        if (t != null) {
            body.taskInfo.failure = Utils.toServiceErrorResponse(t);
        } else {
            ServiceErrorResponse rsp = new ServiceErrorResponse();
            rsp.message = errMsg;
            body.taskInfo.failure = rsp;
        }

        sendRequest(Operation.createPatch(getUri())
                .setBody(body)
                .setCompletion((op, ex) -> {
                    if (ex != null) {
                        logWarning("Self patch failed: %s", Utils.toString(ex));
                    }
                }));
    }

    protected void notifyCallerService(T state) {
        logInfo("Callback to [%s] with state [%s] ",
                state.serviceTaskCallback.serviceSelfLink, state.taskInfo.stage);

        ServiceTaskCallbackResponse callbackResponse;
        switch (state.taskInfo.stage) {
        case FINISHED:
            callbackResponse = getFinishedCallbackResponse(state);
            break;
        case CANCELLED:
            return;
        case FAILED:
        default:
            callbackResponse = getFailedCallbackResponse(state);
            break;
        }

        // copy the state custom properties
        callbackResponse.customProperties = mergeCustomProperties(
                callbackResponse.customProperties, state.customProperties);

        if (state.serviceTaskCallback.isEmpty()) {
            return;
        }

        if (state.serviceTaskCallback.isExternal()) {
            sendRequestStateToExternalUrl(state.serviceTaskCallback.serviceSelfLink, state);
        } else {
            sendRequest(Operation.createPatch(this, state.serviceTaskCallback.serviceSelfLink)
                    .setBody(callbackResponse)
                    .setCompletion((o, e) -> {
                        if (e != null) {
                            logWarning("Notifying parent task %s failed: %s", o.getUri(),
                                    Utils.toString(e));
                        }
                    }));
        }
    }

    protected ServiceTaskCallbackResponse getFailedCallbackResponse(T state) {
        return state.serviceTaskCallback.getFailedResponse(state.taskInfo.failure);
    }

    protected ServiceTaskCallbackResponse getFinishedCallbackResponse(T state) {
        return state.serviceTaskCallback.getFinishedResponse();
    }

    private void sendRequestStateToExternalUrl(String callbackReference, T state) {
        // send put with the RequestState as the body
        logInfo("Calling callback URI: %s", callbackReference);

        final ServiceClient client = getServiceClient();

        try {
            URI callbackUri = URI.create(callbackReference);
            client.send(Operation.createPost(callbackUri)
                    .setBody(state)
                    .setReferer(this.getUri())
                    .forceRemote()
                    .setCompletion((op, ex) -> {
                        if (ex != null) {
                            logSevere("Failure calling callback '%s' for registry state: %s",
                                    op.getUri(), Utils.toString(ex));
                        }
                    }));
        } catch (Exception e) {
            logSevere(e);
        }
    }

    private ServiceClient getServiceClient() {
        if (serviceClient == null) {
            synchronized (AbstractTaskStatefulService.class) {
                if (serviceClient == null) {
                    serviceClient = ServiceClientFactory.createServiceClient(
                            ServerX509TrustManager.create(getHost()), null);
                }
            }
        }
        return serviceClient;
    }

    public static void stop() {
        synchronized (AbstractTaskStatefulService.class) {
            if (serviceClient != null) {
                try {
                    serviceClient.stop();
                    serviceClient = null;
                } catch (Exception e) {
                    Utils.logWarning("Failure stoping callback service client: %s",
                            Utils.toString(e));
                }
            }
        }
    }

    protected TaskStatusState fromTask(TaskServiceDocument<E> state) {
        return fromTask(new TaskStatusState(), state);
    }

    protected <S extends TaskStatusState> S fromTask(S taskStatus, TaskServiceDocument<E> state) {
        taskStatus.documentSelfLink = Service.getId(state.documentSelfLink);
        taskStatus.phase = displayName;
        taskStatus.taskInfo = state.taskInfo;
        taskStatus.subStage = state.taskSubStage.name();
        taskStatus.tenantLinks = state.tenantLinks;
        if (state.customProperties != null) {
            taskStatus.eventLogLink = state.customProperties.get(
                    TaskStatusState.FIELD_NAME_EVENT_LOG_LINK);
        }

        // task progress is the current stage divided by number of normal stages (not including the
        // error one)
        taskStatus.progress = 100 * state.taskSubStage.ordinal()
                / (state.taskSubStage.getClass().getEnumConstants().length - 2);

        if (taskStatus.progress > 100) {
            // reached error state
            taskStatus.progress = 100;
        }

        return taskStatus;
    }

    protected T createUpdateSubStageTask(T state, E subStage) {
        try {
            @SuppressWarnings("unchecked")
            T body = (T) state.getClass().newInstance();
            body.taskInfo = state.taskInfo;
            body.taskSubStage = subStage;
            body.requestTrackerLink = state.requestTrackerLink;
            return body;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
