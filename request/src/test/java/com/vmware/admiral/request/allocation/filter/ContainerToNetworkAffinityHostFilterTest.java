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

package com.vmware.admiral.request.allocation.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.container.CompositeComponentFactoryService;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ServiceNetwork;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService.ContainerNetworkDescription;
import com.vmware.admiral.compute.container.network.ContainerNetworkService;
import com.vmware.admiral.compute.container.network.ContainerNetworkService.ContainerNetworkState;
import com.vmware.admiral.request.allocation.filter.HostSelectionFilter.HostSelection;
import com.vmware.admiral.request.util.TestRequestStateFactory;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.xenon.common.UriUtils;

public class ContainerToNetworkAffinityHostFilterTest extends BaseAffinityHostFilterTest {

    @Test
    public void testFilterDoesNotAffectHosts() throws Throwable {
        assertEquals(3, initialHostLinks.size());

        ContainerDescription desc = createDescription(new String[] {});
        createContainer(desc, initialHostLinks.get(0));
        createContainer(desc, initialHostLinks.get(1));

        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        Map<String, HostSelection> selected = filter();
        assertEquals(3, selected.size());
    }

    @Test
    public void testResolveContainerActualStateNames() throws Throwable {
        ContainerNetworkDescription networkDescription1 = createNetworkDescription("my-test-net");
        String randomName = networkDescription1.name + "-name35";
        ContainerNetworkState networkState1 = createNetwork(networkDescription1, randomName);

        ContainerNetworkDescription networkDescription2 = createNetworkDescription(
                "my-other-test-net");
        randomName = networkDescription2.name + "-name270";
        ContainerNetworkState networkState2 = createNetwork(networkDescription2, randomName);

        ContainerDescription desc = createDescription(new String[] { networkDescription1.name,
                networkDescription2.name });

        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        Map<String, HostSelection> selected = filter();
        assertEquals(1, selected.size());

        for (HostSelection hs : selected.values()) {
            String mappedName = hs.mapNames(new String[] { networkDescription1.name })[0];
            assertEquals(networkState1.name, mappedName);

            mappedName = hs.mapNames(new String[] { networkDescription2.name })[0];
            assertEquals(networkState2.name, mappedName);
        }
    }

    @Test
    public void testFilterHostsWhenNoClustersAvailable() throws Throwable {
        ContainerDescription desc = createContainerWithNetworksDescription();
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        // One host is selected randomly from the initialHostLinks
        Map<String, HostSelection> selected = filter();
        assertEquals(1, selected.size());
    }

    @Test
    public void testFilterHostsWhenAlsoAClusterAvailable() throws Throwable {
        ContainerDescription desc = createContainerWithNetworksDescription();
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        // Add 3 hostLinks with hosts creating a KV store cluster
        expectedLinks = new ArrayList<>();
        expectedLinks.add(createDockerHostWithKVStore("kvstore1"));
        expectedLinks.add(createDockerHostWithKVStore("kvstore1"));

        initialHostLinks.addAll(expectedLinks);

        // The cluster is selected from the initialHostLinks
        Map<String, HostSelection> selected = filter();
        assertEquals(2, selected.size());
    }

    @Test
    public void testFilterHostsWhenAlsoMultipleClustersOfTheSameSizeAvailable() throws Throwable {
        ContainerDescription desc = createContainerWithNetworksDescription();
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        // Add 2 sets of 3 hostLinks with hosts creating 2 KV store clusters
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));

        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));

        // One cluster is selected randomly from the initialHostLinks
        Map<String, HostSelection> selected = filter();
        assertEquals(3, selected.size());

        // And all the nodes from the cluster use the same KV store
        Iterator<HostSelection> it = selected.values().iterator();
        String cs = it.next().clusterStore;
        while (it.hasNext()) {
            assertTrue(it.next().clusterStore.equals(cs));
        }
    }

    @Test
    public void testFilterHostsWhenAlsoMultipleClustersOfDifferentSizesAvailable()
            throws Throwable {
        ContainerDescription desc = createContainerWithNetworksDescription();
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        // Add 3 sets of 3, 4 and 5 hostLinks with hosts creating 3 KV store clusters
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));

        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));

        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));

        // One cluster is selected randomly from the initialHostLinks
        Map<String, HostSelection> selected = filter();
        assertTrue(3 <= selected.size() && selected.size() <= 5);

        // And all the nodes from the cluster use the same KV store
        Iterator<HostSelection> it = selected.values().iterator();
        String cs = it.next().clusterStore;
        while (it.hasNext()) {
            assertTrue(it.next().clusterStore.equals(cs));
        }
    }

    @Test
    public void testSelectHostsWhenOnlyClustersAvailable() throws Throwable {
        ContainerDescription desc = createContainerWithNetworksDescription();
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        initialHostLinks = new ArrayList<>();

        // Add 3 sets of 3, 4 and 5 hostLinks with hosts creating 3 KV store clusters
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore1"));

        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore2"));

        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));
        initialHostLinks.add(createDockerHostWithKVStore("kvstore3"));

        // One cluster is selected randomly from the initialHostLinks
        Map<String, HostSelection> selected = filter();
        assertTrue(3 <= selected.size() && selected.size() <= 5);

        // And all the nodes from the cluster use the same KV store
        Iterator<HostSelection> it = selected.values().iterator();
        String cs = it.next().clusterStore;
        while (it.hasNext()) {
            assertTrue(it.next().clusterStore.equals(cs));
        }
    }

    @Test
    public void testInactiveWithoutNetworks() throws Throwable {
        ContainerDescription desc = createDescription(new String[] {});
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertFalse(filter.isActive());
    }

    @Test
    public void testAffinityConstraintsToNetworks() throws Throwable {
        ContainerDescription desc = createDescription(new String[] { "net1", "net2", "net3" });
        filter = new ContainerToNetworkAffinityHostFilter(host, desc);
        assertTrue(filter.isActive());

        Set<String> affinityConstraintsKeys = filter.getAffinityConstraints().keySet();

        HashSet<Object> expectedNets = new HashSet<>();
        expectedNets.add("net1");
        expectedNets.add("net2");
        expectedNets.add("net3");
        assertEquals(expectedNets, affinityConstraintsKeys);
    }

    private ContainerDescription createDescription(String[] networkNames)
            throws Throwable {
        ContainerDescription desc = TestRequestStateFactory.createContainerDescription();
        desc.documentSelfLink = UUID.randomUUID().toString();
        desc.name = desc.documentSelfLink;

        Map<String, ServiceNetwork> serviceNetworks = new HashMap<String, ServiceNetwork>();
        for (String networkName : networkNames) {
            serviceNetworks.put(networkName, new ServiceNetwork());
        }
        desc.networks = serviceNetworks;

        desc = doPost(desc, ContainerDescriptionService.FACTORY_LINK);
        assertNotNull(desc);
        addForDeletion(desc);

        return desc;
    }

    private ContainerNetworkDescription createNetworkDescription(String networkName)
            throws Throwable {
        ContainerNetworkDescription desc = new ContainerNetworkDescription();
        desc.documentSelfLink = UUID.randomUUID().toString();
        desc.name = networkName;

        desc = doPost(desc, ContainerNetworkDescriptionService.FACTORY_LINK);
        assertNotNull(desc);
        addForDeletion(desc);

        return desc;
    }

    private ContainerNetworkState createNetwork(ContainerNetworkDescription desc, String name)
            throws Throwable {
        ContainerNetworkState containerNetwork = new ContainerNetworkState();
        containerNetwork.descriptionLink = desc.documentSelfLink;
        containerNetwork.id = UUID.randomUUID().toString();
        containerNetwork.name = name;
        containerNetwork.compositeComponentLink = UriUtils.buildUriPath(
                CompositeComponentFactoryService.SELF_LINK, state.contextId);
        containerNetwork = doPost(containerNetwork, ContainerNetworkService.FACTORY_LINK);
        assertNotNull(containerNetwork);
        addForDeletion(containerNetwork);
        return containerNetwork;
    }

    private String createDockerHostWithKVStore(String kvStore) throws Throwable {
        String hostLink = createDockerHost(createDockerHostDescription(), createResourcePool(),
                true).documentSelfLink;

        ComputeState csPatch = new ComputeState();

        csPatch.documentSelfLink = hostLink;
        csPatch.customProperties = new HashMap<>();
        csPatch.customProperties.put(ContainerHostService.DOCKER_HOST_CLUSTER_STORE_PROP_NAME,
                kvStore);

        doPatch(csPatch, hostLink);

        return hostLink;
    }

    private ContainerDescription createContainerWithNetworksDescription() throws Throwable {
        ContainerNetworkDescription netDesc1 = createNetworkDescription("my-net-1");
        ContainerNetworkDescription netDesc2 = createNetworkDescription("my-net-2");
        ContainerDescription desc = createDescription(
                new String[] { netDesc1.name, netDesc2.name });
        return desc;
    }
}
