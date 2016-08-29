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

package com.vmware.admiral.request.composition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.common.util.UriUtilsExtended;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentService.CompositeComponent;
import com.vmware.admiral.compute.container.CompositeDescriptionService.CompositeDescription;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.GroupResourcePolicyService;
import com.vmware.admiral.compute.container.GroupResourcePolicyService.GroupResourcePolicyState;
import com.vmware.admiral.request.RequestBaseTest;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.service.test.MockDockerAdapterService;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.test.TestContext;
import com.vmware.xenon.services.common.QueryTask;

public class CompositionTaskServiceTest extends RequestBaseTest {

    private static final String COMPUTE_STATE_PACKAGE = "com:vmware:photon:controller:model:resources:ComputeService:ComputeState";

    @Override
    @Before
    public void setUp() throws Throwable {
        super.setUp();

        // create a single powered-on compute available for placement
        createVmGuestCompute(true);
    }

    @Test
    public void testWithNoDescs() throws Throwable {
        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE);

        RequestBrokerState request = startRequest(compositeDesc);
        request = waitForTaskError(request.documentSelfLink, RequestBrokerState.class);
        assertNull(request.resourceLinks);
    }

    @Test
    public void testWithSingleDesc() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");

        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE,
                desc1);

        RequestBrokerState request = startRequest(compositeDesc);
        request = waitForTaskSuccess(request.documentSelfLink, RequestBrokerState.class);

        assertValidRequest(request, 1);
    }

    @Test
    public void testWithSingleCompute() throws Throwable {
        ComputeDescription compute = TestRequestStateFactory.createDockerHostDescription();
        compute.instanceAdapterReference = UriUtilsExtended.buildUri(host,
                ManagementUriParts.ADAPTER_DOCKER);
        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.COMPUTE_TYPE,
                compute);
        RequestBrokerState request = startComputeRequest(compositeDesc);
        request = waitForTaskSuccess(request.documentSelfLink, RequestBrokerState.class);
        ComputeState container = getDocument(ComputeState.class, request.resourceLinks.get(0));
        assertNotNull(container);
        assertEquals(COMPUTE_STATE_PACKAGE, container.documentKind);
        assertTrue(container.descriptionLink.contains(compute.documentSelfLink));
    }

    @Test
    public void testWithMultipleComputes() throws Throwable {

        ComputeDescription compute1 = TestRequestStateFactory.createDockerHostDescription();
        compute1.instanceAdapterReference = UriUtilsExtended.buildUri(host,
                ManagementUriParts.ADAPTER_DOCKER);

        ComputeDescription compute2 = TestRequestStateFactory.createDockerHostDescription();
        compute2.instanceAdapterReference = UriUtilsExtended.buildUri(host,
                ManagementUriParts.ADAPTER_DOCKER);

        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.COMPUTE_TYPE,
                compute1,
                compute2);
        RequestBrokerState request = startComputeRequest(compositeDesc);
        request = waitForTaskSuccess(request.documentSelfLink, RequestBrokerState.class);

        for (String containerLink : request.resourceLinks) {
            ComputeState container = getDocument(ComputeState.class, containerLink);
            assertNotNull(container);
            assertEquals(COMPUTE_STATE_PACKAGE, container.documentKind);
            addForDeletion(container);
        }
    }

    @Test
    @Ignore("This test was written with the assumption that we provision only one resource type of the container description")
    public void tesComputeRequestWithContainerDesc() throws Throwable {

        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");

        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE,
                desc1);

        // Call RequestBroker with resourceType="Compute" for Composition task with ContainerDesc
        // instead of ComputeDesc.
        RequestBrokerState request = startComputeRequest(compositeDesc);
        request = waitForTaskError(request.documentSelfLink, RequestBrokerState.class);
        String errorMsg = String.format(
                "No computeDescriptions found for links: [/resources/container-descriptions/%s]",
                desc1.documentSelfLink);
        assertEquals(errorMsg, request.taskInfo.failure.message);
    }

    @Test
    public void testWithDependentDesc() throws Throwable {
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2");
        desc2.affinity = new String[] { desc1.name };
        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE, desc1,
                desc2);

        RequestBrokerState request = startRequest(compositeDesc);
        request = waitForTaskSuccess(request.documentSelfLink, RequestBrokerState.class);

        assertValidRequest(request, 2);
    }

    @Test
    public void testWithMultipleDependentDescs() throws Throwable {
        addAdditionalPolicy();

        CompositeDescription compositeDesc = createComplexCompositeDesc();

        RequestBrokerState request = startRequest(compositeDesc);
        request = waitForTaskSuccess(request.documentSelfLink, RequestBrokerState.class);

        assertValidRequest(request, compositeDesc.descriptionLinks.size());
    }

    @Test
    public void testWithPoliciesNotEnoughForSomeComponents() throws Throwable {
        CountDownLatch latch = new CountDownLatch(3);

        host.log(">>>>>>>>>>>>>>>> testWithPoliciesNotEnoughForSomeComponents <<<<<<<<<<<<");
        CompositeDescription compositeDesc = createComplexCompositeDesc();
        verifyContainerStatesRemoved(compositeDesc, latch);

        // Make sure that policies are less than the requested containers in order to fail
        // allocation
        assertTrue(
                compositeDesc.descriptionLinks.size() > groupPolicyState.availableInstancesCount);

        // fail on host placement:
        RequestBrokerState request = startRequest(compositeDesc);
        waitForTaskError(request.documentSelfLink, RequestBrokerState.class);

        verifyContainerStatesRemoved(compositeDesc, latch);

        delete(DEFAULT_GROUP_RESOURCE_POLICY);
        // fail on policies not available:
        request = startRequest(compositeDesc);
        host.log(
                ">>>>>>>>>>>>>>>> testWithPoliciesNotEnoughForSomeComponents: Test request started: %s <<<<<<<<<<<<",
                request.documentSelfLink);
        waitForTaskError(request.documentSelfLink, RequestBrokerState.class);

        verifyContainerStatesRemoved(compositeDesc, latch);

        // verify host is not stopped before container states are deleted
        latch.await();
    }

    @Test
    public void testWithComponentsProvisioningFailures() throws Throwable {
        CountDownLatch latch = new CountDownLatch(2);

        host.log(">>>>>>>>>>>>>>>> testWithPoliciesNotEnoughForSomeComponents <<<<<<<<<<<<");
        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");
        ContainerDescription desc2 = TestRequestStateFactory.createContainerDescription("name2");
        desc2.affinity = new String[] { desc1.name };

        ContainerDescription desc3 = TestRequestStateFactory.createContainerDescription("name3");
        ContainerDescription desc4 = TestRequestStateFactory.createContainerDescription("name4");
        ContainerDescription desc5 = TestRequestStateFactory.createContainerDescription("name5");

        desc1.volumesFrom = new String[] { desc3.name };
        desc1.affinity = new String[] { desc4.name };
        desc2.affinity = new String[] { desc5.name };

        desc4.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED, "simulate failure");
        desc2.customProperties.put(MockDockerAdapterService.FAILURE_EXPECTED, "simulate failure");

        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE, desc1,
                desc2, desc3, desc4, desc5);

        verifyContainerStatesRemoved(compositeDesc, latch);

        RequestBrokerState request = startRequest(compositeDesc);
        host.log(
                ">>>>>>>>>>>>>>>> testWithPoliciesNotEnoughForSomeComponents: Test request started: %s <<<<<<<<<<<<",
                request.documentSelfLink);
        waitForTaskError(request.documentSelfLink, RequestBrokerState.class);

        verifyContainerStatesRemoved(compositeDesc, latch);

        // verify host is not stopped before container states are deleted
        latch.await();
    }

    @Test
    public void testCleanUpCompositeOnFailureToFindPolicies() throws Throwable {
        // make sure no policies are available
        removeAllPolicies();

        ContainerDescription desc1 = TestRequestStateFactory.createContainerDescription("name1");
        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE,
                desc1);

        RequestBrokerState request = startRequest(compositeDesc);
        waitForTaskError(request.documentSelfLink, RequestBrokerState.class);

        verifyCompositesRemoved();
    }

    @Test
    public void testCleanUpCompositeOnFailureWithDuplicateContainerNames() throws Throwable {
        String containerName = "duplicate_name";

        ContainerDescription desc1 = TestRequestStateFactory
                .createContainerDescription(containerName);
        ContainerDescription desc2 = TestRequestStateFactory
                .createContainerDescription(containerName);
        CompositeDescription compositeDesc = createCompositeDesc(ResourceType.CONTAINER_TYPE, desc1,
                desc2);

        RequestBrokerState request = startRequest(compositeDesc);
        waitForTaskError(request.documentSelfLink, RequestBrokerState.class);

        verifyCompositesRemoved();
    }

    private CompositeDescription createComplexCompositeDesc() throws Throwable {
        // Graph:
        // ..................................
        // .1...d0.........d1............d2..
        // ................/\.........../....
        // .2............d3..d4........d5....
        // ............./............../.....
        // .3.........d6..............d7.....
        // ........../..\............/..\....
        // .4......d8....d9........d10..d11..
        // ......./.|.\.......................
        // .5..d12.d13.d14...................
        // ..................................

        ContainerDescription[] descs = new ContainerDescription[15];
        // level 1:
        descs[0] = TestRequestStateFactory.createContainerDescription("name0");
        descs[1] = TestRequestStateFactory.createContainerDescription("name1");
        descs[2] = TestRequestStateFactory.createContainerDescription("name2");

        // level 2:
        descs[3] = TestRequestStateFactory.createContainerDescription("name3");
        descs[4] = TestRequestStateFactory.createContainerDescription("name4");
        descs[5] = TestRequestStateFactory.createContainerDescription("name5");

        descs[1].volumesFrom = new String[] { descs[3].name };
        descs[1].affinity = new String[] { descs[4].name };
        descs[2].affinity = new String[] { descs[5].name };

        // level 3:
        descs[6] = TestRequestStateFactory.createContainerDescription("name6");
        descs[7] = TestRequestStateFactory.createContainerDescription("name7");

        descs[3].volumesFrom = new String[] { descs[6].name };
        descs[5].affinity = new String[] { descs[7].name };

        // level 4:
        descs[8] = TestRequestStateFactory.createContainerDescription("name8");
        descs[9] = TestRequestStateFactory.createContainerDescription("name9");
        descs[10] = TestRequestStateFactory.createContainerDescription("name10");
        descs[11] = TestRequestStateFactory.createContainerDescription("name11");

        descs[6].affinity = new String[] { descs[9].name, descs[8].name };
        descs[7].affinity = new String[] { descs[10].name, descs[11].name };

        // level 5:
        descs[12] = TestRequestStateFactory.createContainerDescription("name12");
        descs[13] = TestRequestStateFactory.createContainerDescription("name13");
        descs[14] = TestRequestStateFactory.createContainerDescription("name14");

        descs[8].volumesFrom = new String[] { descs[12].name, descs[13].name };
        descs[8].affinity = new String[] { descs[14].name };

        return createCompositeDesc(ResourceType.CONTAINER_TYPE, descs);
    }

    private RequestBrokerState startRequest(CompositeDescription desc) throws Throwable {
        RequestBrokerState request = TestRequestStateFactory.createRequestState();
        request.tenantLinks = groupPolicyState.tenantLinks;
        request.resourceDescriptionLink = desc.documentSelfLink;

        request = super.startRequest(request);
        return request;
    }

    private RequestBrokerState startComputeRequest(CompositeDescription desc) throws Throwable {
        RequestBrokerState request = TestRequestStateFactory.createComputeRequestState();
        request.tenantLinks = groupPolicyState.tenantLinks;
        request.resourceDescriptionLink = desc.documentSelfLink;

        request = super.startRequest(request);
        return request;
    }

    private void assertValidRequest(RequestBrokerState requestBrokerState, int expectedCount)
            throws Throwable {
        assertNotNull(requestBrokerState);
        assertNotNull("Resource links null for requestBroker: "
                + requestBrokerState.documentSelfLink,
                requestBrokerState.resourceLinks);
        assertEquals(expectedCount, requestBrokerState.resourceLinks.size());

        for (String containerLink : requestBrokerState.resourceLinks) {
            ContainerState container = getDocument(ContainerState.class, containerLink);
            assertNotNull(container);
            addForDeletion(container);
        }
    }

    private void addAdditionalPolicy() throws Throwable {
        GroupResourcePolicyState additionalPolicy = TestRequestStateFactory
                .createGroupResourcePolicyState();
        additionalPolicy.resourcePoolLink = resourcePool.documentSelfLink;
        additionalPolicy = doPost(additionalPolicy, GroupResourcePolicyService.FACTORY_LINK);
        addForDeletion(additionalPolicy);
        assertNotNull(additionalPolicy);
    }

    private void verifyContainerStatesRemoved(CompositeDescription compositeDesc,
            CountDownLatch latch) throws Throwable {
        ServiceDocumentQuery<ContainerState> query = new ServiceDocumentQuery<>(host,
                ContainerState.class);
        QueryTask q = QueryUtil.buildQuery(ContainerState.class, true);
        QueryUtil.addListValueClause(q, ContainerState.FIELD_NAME_DESCRIPTION_LINK,
                compositeDesc.descriptionLinks);
        List<String> containerLinks = new ArrayList<>();
        waitFor("ContainerStates must be removed.", () -> {

            host.testStart(1);
            query.query(q, (r) -> {
                if (r.hasException()) {
                    host.failIteration(r.getException());
                } else if (r.hasResult()) {
                    containerLinks.add(r.getDocumentSelfLink());
                } else {
                    host.completeIteration();
                }
            });
            host.testWait();
            latch.countDown();

            host.log("Container count: %s", containerLinks.size());
            return containerLinks.isEmpty();
        });
    }

    private void removeAllPolicies() throws Throwable {
        QueryTask q = QueryUtil.buildQuery(GroupResourcePolicyState.class, false);

        ServiceDocumentQuery<?> query = new ServiceDocumentQuery<>(host,
                GroupResourcePolicyState.class);
        List<String> policyLinks = new ArrayList<>();

        TestContext ctx = testCreate(1);
        query.query(q, (r) -> {
            if (r.hasException()) {
                host.log(Level.SEVERE,
                        "Exception during search for GroupResourcePolicyStates",
                        r.getException().getMessage());
                ctx.failIteration(r.getException());
            } else if (r.hasResult()) {
                policyLinks.add(r.getDocumentSelfLink());
            } else {
                ctx.completeIteration();
            }
        });
        ctx.await();

        for (String selfLink : policyLinks) {
            delete(selfLink);
        }
    }

    private void verifyCompositesRemoved() throws Throwable {
        QueryTask q = QueryUtil.buildQuery(CompositeComponent.class, false);

        ServiceDocumentQuery<?> query = new ServiceDocumentQuery<>(host,
                CompositeComponent.class);

        TestContext ctx = testCreate(1);
        query.query(q, (r) -> {
            if (r.hasException()) {
                host.log(Level.SEVERE,
                        "Exception during search for CompositeComponents",
                        r.getException().getMessage());
                ctx.failIteration(r.getException());
            } else if (r.hasResult()) {
                ctx.failIteration(new IllegalStateException("CompositeComponents found!"));
            } else {
                ctx.completeIteration();
            }
        });
        ctx.await();
    }
}
