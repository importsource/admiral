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

package com.vmware.admiral.host;

import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.EnvironmentMappingService;
import com.vmware.admiral.compute.HostConfigCertificateDistributionService;
import com.vmware.admiral.compute.RegistryConfigCertificateDistributionService;
import com.vmware.admiral.compute.RegistryHostConfigService;
import com.vmware.admiral.compute.container.CompositeComponentFactoryService;
import com.vmware.admiral.compute.container.CompositeDescriptionCloneService;
import com.vmware.admiral.compute.container.CompositeDescriptionFactoryService;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerHostDataCollectionService;
import com.vmware.admiral.compute.container.ContainerHostNetworkConfigFactoryService;
import com.vmware.admiral.compute.container.ContainerLogService;
import com.vmware.admiral.compute.container.ContainerShellService;
import com.vmware.admiral.compute.container.DeploymentPolicyService;
import com.vmware.admiral.compute.container.ExposedServiceDescriptionService.ExposedServiceDescriptionFactoryService;
import com.vmware.admiral.compute.container.GroupResourcePolicyService;
import com.vmware.admiral.compute.container.HostContainerListDataCollection.HostContainerListDataCollectionFactoryService;
import com.vmware.admiral.compute.container.ShellContainerExecutorService;
import com.vmware.admiral.compute.container.TemplateSearchService;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService;
import com.vmware.admiral.compute.container.network.ContainerNetworkReconfigureService;
import com.vmware.admiral.compute.container.network.ContainerNetworkService;
import com.vmware.admiral.compute.container.volume.ContainerVolumeService;
import com.vmware.admiral.compute.content.CompositeDescriptionContentService;
import com.vmware.admiral.compute.endpoint.EndpointService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;

public class HostInitComputeServicesConfig extends HostInitServiceHelper {

    public static void startServices(ServiceHost host) {
        startServices(host,
                ContainerFactoryService.class,
                ContainerHostService.class,
                ContainerHostNetworkConfigFactoryService.class,
                ContainerNetworkReconfigureService.class,
                ExposedServiceDescriptionFactoryService.class,
                HostContainerListDataCollectionFactoryService.class,
                RegistryHostConfigService.class,
                CompositeDescriptionFactoryService.class,
                CompositeDescriptionCloneService.class,
                CompositeDescriptionContentService.class, TemplateSearchService.class,
                CompositeComponentFactoryService.class, ContainerLogService.class,
                ContainerShellService.class, ShellContainerExecutorService.class,
                HostConfigCertificateDistributionService.class,
                RegistryConfigCertificateDistributionService.class,
                ComputeInitialBootService.class);

        startServiceFactories(host, ContainerDescriptionService.class,
                GroupResourcePolicyService.class,
                ContainerHostDataCollectionService.class,
                EnvironmentMappingService.class, DeploymentPolicyService.class,
                EndpointService.class,
                ContainerNetworkService.class,
                ContainerVolumeService.class,
                ContainerNetworkDescriptionService.class);

        // start initialization of system documents
        host.sendRequest(Operation.createPost(
                UriUtils.buildUri(host, ComputeInitialBootService.class))
                .setReferer(host.getUri())
                .setBody(new ServiceDocument()));

    }
}
