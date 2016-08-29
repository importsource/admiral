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

package com.vmware.admiral.compute.container;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.common.util.ServiceDocumentQuery;
import com.vmware.admiral.compute.container.CompositeComponentService.CompositeComponent;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.UriUtils;

public class CompositeComponentServiceTest extends ComputeBaseTest {

    private List<String> forDeletion;
    private CompositeComponent compositeComponent;

    @Before
    public void setUp() throws Throwable {
        waitForServiceAvailability(CompositeComponentFactoryService.SELF_LINK);
        waitForServiceAvailability(ContainerFactoryService.SELF_LINK);

        forDeletion = new ArrayList<>();
    }

    @After
    public void tearDown() throws Throwable {
        for (String selfLink : forDeletion) {
            delete(selfLink);
        }
    }

    @Test
    public void testCompositeComponentServices() throws Throwable {
        verifyService(
                CompositeComponentFactoryService.class,
                CompositeComponent.class,
                (prefix, index) -> {
                    CompositeComponent compositeComp = new CompositeComponent();
                    compositeComp.name = prefix + "name" + index;
                    compositeComp.compositeDescriptionLink = prefix + "link" + index;

                    return compositeComp;
                },
                (prefix, serviceDocument) -> {
                    CompositeComponent contDesc = (CompositeComponent) serviceDocument;
                    assertTrue(contDesc.name.startsWith(prefix + "name"));
                    assertTrue(contDesc.compositeDescriptionLink.startsWith(prefix + "link"));
                });
    }

    @Test
    public void testShouldUpdateContainerLinksWhenUpdatesToContainers() throws Throwable {
        compositeComponent = createCompositeComponent();
        ContainerState containerState1 = createContainer(compositeComponent.documentSelfLink);

        // add a new container:
        waitFor(() -> {
            compositeComponent = getDocument(CompositeComponent.class,
                    compositeComponent.documentSelfLink);
            if (compositeComponent.componentLinks == null
                    || compositeComponent.componentLinks.isEmpty()) {
                return false;
            }

            if (compositeComponent.componentLinks.size() != 1) {
                return false;
            }
            String containerLink = compositeComponent.componentLinks.get(0);

            return containerState1.documentSelfLink.equals(containerLink);
        });

        ContainerState containerState2 = createContainer(compositeComponent.documentSelfLink);

        // add a second new container:
        waitFor(() -> {
            compositeComponent = getDocument(CompositeComponent.class,
                    compositeComponent.documentSelfLink);

            int count = 0;
            for (String componentLink : compositeComponent.componentLinks) {
                if (componentLink.equals(containerState1.documentSelfLink)
                        || componentLink.equals(containerState2.documentSelfLink)) {
                    count++;
                    continue;
                }
                return false;
            }

            return count == 2;
        });

        // delete a container:
        delete(containerState2.documentSelfLink);

        waitFor(() -> {
            compositeComponent = getDocument(CompositeComponent.class,
                    compositeComponent.documentSelfLink);
            if (compositeComponent.componentLinks.size() != 1) {
                return false;
            }
            String containerLink = compositeComponent.componentLinks.get(0);

            return containerState1.documentSelfLink.equals(containerLink);
        });

        // update a container to remove the composite link
        containerState1.compositeComponentLink = null;
        doOperation(containerState1, UriUtils.buildUri(host, containerState1.documentSelfLink),
                false, Action.PUT);

        //test if the CompositeComponent has been deleted.
        waitFor(() -> {
            ServiceDocumentQuery<CompositeComponent> query = new ServiceDocumentQuery<>(host,
                    CompositeComponent.class);
            AtomicBoolean deleted = new AtomicBoolean();
            host.testStart(1);
            query.queryDocument(compositeComponent.documentSelfLink, (r) -> {
                if (r.hasException()) {
                    host.failIteration(r.getException());
                } else if (r.hasResult()) {
                    deleted.set(false);
                    host.completeIteration();
                } else {
                    deleted.set(true);
                    host.completeIteration();
                }
            });
            host.testWait();
            return deleted.get();
        });

        //create compositeComponent again:
        compositeComponent = createCompositeComponent();

        // update a container to add the composite link
        containerState1.compositeComponentLink = compositeComponent.documentSelfLink;
        doOperation(containerState1, UriUtils.buildUri(host, containerState1.documentSelfLink),
                false, Action.PATCH);

        waitFor(() -> {
            compositeComponent = getDocument(CompositeComponent.class,
                    compositeComponent.documentSelfLink);

            if (compositeComponent.componentLinks == null
                    || compositeComponent.componentLinks.size() != 1) {
                return false;
            }
            String containerLink = compositeComponent.componentLinks.get(0);

            return containerState1.documentSelfLink.equals(containerLink);
        });

        delete(containerState1.documentSelfLink);

        //test if the CompositeComponent has been deleted.
        waitFor(() -> {
            ServiceDocumentQuery<CompositeComponent> query = new ServiceDocumentQuery<>(host,
                    CompositeComponent.class);
            AtomicBoolean deleted = new AtomicBoolean();
            host.testStart(1);
            query.queryDocument(compositeComponent.documentSelfLink, (r) -> {
                if (r.hasException()) {
                    host.failIteration(r.getException());
                } else if (r.hasResult()) {
                    deleted.set(false);
                    host.completeIteration();
                } else {
                    deleted.set(true);
                    host.completeIteration();
                }
            });
            host.testWait();
            return deleted.get();
        });
    }

    private CompositeComponent createCompositeComponent() throws Throwable {
        compositeComponent = new CompositeComponent();
        compositeComponent.name = "test-name";
        compositeComponent = doPost(compositeComponent, CompositeComponentFactoryService.SELF_LINK);
        forDeletion.add(compositeComponent.documentSelfLink);
        return compositeComponent;
    }

    private ContainerState createContainer(String compositeComponentLink) throws Throwable {
        ContainerState containerState = new ContainerState();
        containerState.id = UUID.randomUUID().toString();
        containerState.names = new ArrayList<>(Arrays.asList("name_" + containerState.id));
        containerState.compositeComponentLink = compositeComponentLink;

        containerState = doPost(containerState, ContainerFactoryService.SELF_LINK);
        forDeletion.add(containerState.documentSelfLink);

        return containerState;
    }
}
