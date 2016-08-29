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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.request.ContainerHostRemovalTaskService.ContainerHostRemovalTaskState;
import com.vmware.admiral.request.RequestBrokerService.RequestBrokerState;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.admiral.service.test.MockDockerAdapterService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;

public class ContainerHostRemovalTaskServiceTest extends RequestBaseTest {
    private RequestBrokerState request;

    @Before
    @Override
    public void setUp() throws Throwable {
        super.setUp();

        request = TestRequestStateFactory.createRequestState();
        request.resourceDescriptionLink = containerDesc.documentSelfLink;
        request.tenantLinks = groupPolicyState.tenantLinks;
        request.resourceCount = 2;
    }

    @Test
    public void testContainerHostRemovalResourceOperationCycle() throws Throwable {
        request = startRequest(request);
        waitForRequestToComplete(request);

        request = getDocument(RequestBrokerState.class, request.documentSelfLink);
        assertNotNull(request);

        // verify the resources are created as expected:
        assertEquals(request.resourceCount, request.resourceLinks.size());
        List<String> containerStateLinks = findResourceLinks(ContainerState.class,
                request.resourceLinks);

        // create a host removal task
        ContainerHostRemovalTaskState state = new ContainerHostRemovalTaskState();
        state.resourceLinks = Collections.singletonList(computeHost.documentSelfLink);
        state = doPost(state, ContainerHostRemovalTaskFactoryService.SELF_LINK);

        assertNotNull("task is null", state);
        waitForTaskSuccess(state.documentSelfLink, ContainerHostRemovalTaskState.class);

        // verify that the container states were removed
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue("ContainerState not removed: " + containerStateLinks,
                containerStateLinks.isEmpty());

        // verify that the host was removed
        Collection<String> computeSelfLinks = findResourceLinks(ComputeState.class,
                Collections.singletonList(computeHost.documentSelfLink));

        assertTrue("ComputeState was not deleted: " + computeSelfLinks, computeSelfLinks.isEmpty());

        // verify that the containers where removed from the docker mock
        Map<String, String> containerRefsByIds = MockDockerAdapterService
                .getContainerIdsWithContainerReferences();
        for (String containerRef : containerRefsByIds.values()) {
            for (String containerLink : containerStateLinks) {
                if (containerRef.endsWith(containerLink)) {
                    fail("Container State not removed with link: " + containerLink);
                }
            }
        }
    }

    @Test
    public void testRequestBrokerContainerHostRemovalResourceOperationCycle() throws Throwable {
        request = startRequest(request);
        waitForRequestToComplete(request);

        request = getDocument(RequestBrokerState.class, request.documentSelfLink);
        assertNotNull(request);

        // verify the resources are created as expected:
        assertEquals(request.resourceCount, request.resourceLinks.size());
        List<String> containerStateLinks = findResourceLinks(ContainerState.class,
                request.resourceLinks);

        // create a host removal task - RequestBroker
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_HOST_TYPE.getName();
        request.resourceLinks = Collections.singletonList(computeHost.documentSelfLink);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;

        request = startRequest(request);
        waitForRequestToComplete(request);

        // verify that the container states were removed
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue("ContainerState not removed: " + containerStateLinks,
                containerStateLinks.isEmpty());

        // verify that the host was removed
        Collection<String> computeSelfLinks = findResourceLinks(ComputeState.class,
                Collections.singletonList(computeHost.documentSelfLink));

        assertTrue("ComputeState was not deleted: " + computeSelfLinks, computeSelfLinks.isEmpty());

        // verify that the containers where removed from the docker mock
        Map<String, String> containerRefsByIds = MockDockerAdapterService
                .getContainerIdsWithContainerReferences();
        for (String containerRef : containerRefsByIds.values()) {
            for (String containerLink : containerStateLinks) {
                if (containerRef.endsWith(containerLink)) {
                    fail("Container State not removed with link: " + containerLink);
                }
            }
        }
    }

    @Test
    public void testRequestBrokerContainerHostRemovalWithSystemContainer() throws Throwable {
        request = startRequest(request);
        waitForRequestToComplete(request);

        request = getDocument(RequestBrokerState.class, request.documentSelfLink);
        assertNotNull(request);

        // create a system container
        ContainerState container = TestRequestStateFactory.createContainer();
        container.descriptionLink = containerDesc.documentSelfLink;
        container.adapterManagementReference = containerDesc.instanceAdapterReference;
        container.groupResourcePolicyLink = groupPolicyState.documentSelfLink;
        container.parentLink = computeHost.documentSelfLink;
        container.system = Boolean.TRUE;
        container = doPost(container, ContainerFactoryService.SELF_LINK);

        // verify the resources are created as expected:
        assertEquals(request.resourceCount, request.resourceLinks.size());
        List<String> containerStateLinks = findResourceLinks(ContainerState.class,
                request.resourceLinks);

        // create a host removal task - RequestBroker
        RequestBrokerState request = new RequestBrokerState();
        request.resourceType = ResourceType.CONTAINER_HOST_TYPE.getName();
        request.resourceLinks = Collections.singletonList(computeHost.documentSelfLink);
        request.operation = RequestBrokerState.REMOVE_RESOURCE_OPERATION;

        request = startRequest(request);
        waitForRequestToComplete(request);

        // verify that the container states were removed
        containerStateLinks = findResourceLinks(ContainerState.class, containerStateLinks);
        assertTrue("ContainerState not removed: " + containerStateLinks,
                containerStateLinks.isEmpty());

        // verify that the host was removed
        Collection<String> computeSelfLinks = findResourceLinks(ComputeState.class,
                Collections.singletonList(computeHost.documentSelfLink));

        assertTrue("ComputeState was not deleted: " + computeSelfLinks, computeSelfLinks.isEmpty());

        // verify that the containers where removed from the docker mock
        Map<String, String> containerRefsByIds = MockDockerAdapterService
                .getContainerIdsWithContainerReferences();
        for (String containerRef : containerRefsByIds.values()) {
            for (String containerLink : containerStateLinks) {
                if (containerRef.endsWith(containerLink)) {
                    fail("Container State not removed with link: " + containerLink);
                }
            }
        }
    }


}
