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

import * as actions from 'actions/Actions';
import services from 'core/services';
import constants from 'core/constants';
import links from 'core/links';
import utils from 'core/utils';
import imageUtils from 'core/imageUtils';
import RegistryStore from 'stores/RegistryStore';
import RequestsStore from 'stores/RequestsStore';
import ResourceGroupsStore from 'stores/ResourceGroupsStore';
import NotificationsStore from 'stores/NotificationsStore';
import EventLogStore from 'stores/EventLogStore';
import ContextPanelStoreMixin from 'stores/mixins/ContextPanelStoreMixin';
import CrudStoreMixin from 'stores/mixins/CrudStoreMixin';
import recommendedImages from 'core/recommendedImages';

const DTO_IMAGE_TYPE = 'CONTAINER_IMAGE_DESCRIPTION';
const DTO_TEMPLATE_TYPE = 'COMPOSITE_DESCRIPTION';

const OPERATION = {
  LIST: 'LIST'
};

const SYSTEM_NETWORK_LINK = '/system-networks-link';

let navigateTemplatesAndOpenRequests = function(request) {
  var openTemplatesUnsubscribe = actions.TemplateActions.openTemplates.listen(() => {
    openTemplatesUnsubscribe();
    this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
    actions.RequestsActions.requestCreated(request);
  });

  actions.NavigationActions.openTemplates();
};

let _enhanceImage = function(image) {
  image.documentId = image.name;
  image.name = imageUtils.getImageName(image.name);
  image.icon = imageUtils.getImageIconLink(image.name);
  image.type = constants.TEMPLATES.TYPES.IMAGE;
};

let _enhanceContainerTemplate = function(containerTemplate, listViewPath) {
  containerTemplate.documentId = utils.getDocumentId(containerTemplate.documentSelfLink);
  containerTemplate.name = containerTemplate.name || containerTemplate.documentId;

  var images = [...new Set(containerTemplate.descriptionImages)];
  containerTemplate.icons = images.map(image => imageUtils.getImageIconLink(image));
  containerTemplate.type = constants.TEMPLATES.TYPES.TEMPLATE;

  services.loadTemplateDescriptionImages(containerTemplate.documentSelfLink)
      .then((result) => {
    let icons = new Set();
    for (var key in result.descriptionImages) {
      if (result.descriptionImages.hasOwnProperty(key)) {
        var icon = imageUtils.getImageIconLink(result.descriptionImages[key]);
        icons.add(icon);
      }
    }
    var selector = this.selectFromData(listViewPath);
    let items = selector.getIn('items');
    if (items) {
      items = items.map((t) => {
        if (t.documentSelfLink === containerTemplate.documentSelfLink) {
          t = t.asMutable();
          t.icons = [...icons];
        }
        return t;
      });
      selector.setIn('items', items);
      this.emitChange();
    }
  });
};

let _enhanceContainerDescription = function(containerDescription, allContainerDescriptions) {
  let containerDescriptionLink = containerDescription.documentSelfLink;
  if (containerDescriptionLink) {
    containerDescription.documentId = utils.getDocumentId(containerDescriptionLink);
  }

  containerDescription.icon = imageUtils.getImageIconLink(containerDescription.image);

  let resultTemplates = allContainerDescriptions.map((containerDescription) => {
    return {
      id: containerDescription.documentSelfLink,
      name: containerDescription.name
    };
  });

  let otherTemplates = resultTemplates;
  if (containerDescriptionLink) {

    otherTemplates = resultTemplates.filter((template) => {
      return template.id !== containerDescriptionLink;
    });
  }

  containerDescription.otherContainers = otherTemplates;
};

let searchImages = function(queryOptions, searchOnlyImages, forContainerDefinition) {
  var listViewPath;
  if (forContainerDefinition) {
    listViewPath = ['selectedItemDetails', 'newContainerDefinition', 'listView'];
  } else {
    listViewPath = ['listView'];
  }

  var operation = this.requestCancellableOperation(OPERATION.LIST, queryOptions);
  if (!operation) {
    return;
  }

  this.setInData(listViewPath.concat(['itemsLoading']), true);

  this.emitChange();

  operation.forPromise(services.loadTemplates(queryOptions)).then((templates) => {
    var resultTemplates = [];
    for (var i = 0; i < templates.length; i++) {
      var template = templates[i];
      if (template.templateType === DTO_IMAGE_TYPE) {
        _enhanceImage(template);
        resultTemplates.push(template);
      } else if (template.templateType === DTO_TEMPLATE_TYPE) {
        _enhanceContainerTemplate.call(this, template, listViewPath);
        resultTemplates.push(template);
      }
    }

    this.setInData(listViewPath.concat(['items']), resultTemplates);
    this.setInData(listViewPath.concat(['itemsLoading']), false);
    this.setInData(listViewPath.concat(['searchedItems']), true);
    this.emitChange();
  }).catch((e) => {
    this.setInData(listViewPath.concat(['items']), []);
    this.setInData(listViewPath.concat(['itemsLoading']), false);
    this.setInData(listViewPath.concat(['searchedItems']), true);
    this.setInData(listViewPath.concat(['error']),
                        e.responseJSON.message || e.statusText);
    this.emitChange();
  });
};

let loadRecommended = function(forContainerDefinition) {
  var listViewPath;
  if (forContainerDefinition) {
    listViewPath = ['selectedItemDetails', 'newContainerDefinition', 'listView'];
  } else {
    listViewPath = ['listView'];
  }

  this.setInData(listViewPath.concat(['items']), recommendedImages.images);
  this.setInData(listViewPath.concat(['searchedItems']), false);
  this.emitChange();
};

let handlePublishTemplate = function(templateDocumentSelfLink, alertObj) {
  if (this.data.selectedItemDetails) {
    // we are in template details view
    this.setInData(['selectedItemDetails', 'alert'], alertObj);
    this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'itemsLoading'], false);
    this.emitChange();
  } else {
    // we are in main templates view
    var templates = utils.getIn(this.getData(), ['listView', 'items']).asMutable({deep: true});
    for (let idx = 0; idx < templates.length; idx++) {
      if (templates[idx].documentSelfLink === templateDocumentSelfLink) {
        templates[idx].alert = alertObj;
        break;
      }
    }

    this.setInData(['listView', 'itemsLoading'], false);
    this.setInData(['listView', 'items'], templates);
    this.emitChange();

    setTimeout(() => {
      var templates = utils.getIn(this.getData(), ['listView', 'items']).asMutable({deep: true});
      let idx;
      for (idx = 0; idx < templates.length; idx++) {
        if (templates[idx].documentSelfLink === templateDocumentSelfLink) {
          templates[idx].alert = alertObj;
          break;
        }
      }
      templates[idx].alert = null;

      this.setInData(['listView', 'items'], templates);
      this.emitChange();
    }, 3000);
  }
};

let getNetworkByName = function(networkDescriptions, name) {
  for (var i = 0; i < networkDescriptions.length; i++) {
    if (networkDescriptions[i].name === name) {
      return networkDescriptions[i];
    }
  }
};

/* Returns the user defined network descriptions (the ones coming from the service)
   together with the system ones that should be available for selection (host, bridge, ....)
*/
let getCompleteNetworkDescriptions = function(containerDescriptions, networkDescriptions) {
  var systemNetworkModes = [constants.NETWORK_MODES.BRIDGE.toLowerCase(),
                            constants.NETWORK_MODES.HOST.toLowerCase()];

  var result = networkDescriptions;

  for (let i = 0; i < result.length; i++) {
    var network = result[i];
    if (network.name) {
      let index = systemNetworkModes.indexOf(network.name.toLowerCase());
      if (index !== -1) {
        systemNetworkModes.splice(index, 1);
      }
    }
  }

  for (let i = 0; i < containerDescriptions.length; i++) {
    var cd = containerDescriptions[i];
    var networkNames = getNetworkNamesOfContainer(cd);

    for (let ni = 0; ni < networkNames.length; ni++) {
      var networkName = networkNames[ni];
      let index = systemNetworkModes.indexOf(networkName);
      if (index !== -1) {
        systemNetworkModes.splice(index, 1);
        result.push({
          documentSelfLink: SYSTEM_NETWORK_LINK + '/' + networkName,
          name: networkName
        });
      }
    }
  }

  return result;
};

let getAllNetworkDescriptions = function(networkDescriptions) {
  var systemNetworkModes = [constants.NETWORK_MODES.BRIDGE.toLowerCase(),
                            constants.NETWORK_MODES.NONE.toLowerCase(),
                            constants.NETWORK_MODES.HOST.toLowerCase()];

  var result = [];

  for (let i = 0; i < systemNetworkModes.length; i++) {
    let name = systemNetworkModes[i];
    result.push({
      name: name,
      label: i18n.t('app.container.request.inputs.networkModeTypes.' + name)
    });
  }

  for (let i = 0; i < networkDescriptions.length; i++) {
    var network = networkDescriptions[i];
    if (network.name && systemNetworkModes.indexOf(network.name.toLowerCase()) === -1) {
      result.push(network);
    }
  }

  return result;
};

let getNetworkLinks = function(containerDescriptions, networkDescriptions) {
  var networkLinks = {};
  for (var i = 0; i < containerDescriptions.length; i++) {
    var cd = containerDescriptions[i];
    var networkNames = getNetworkNamesOfContainer(cd);

    for (var ni = 0; ni < networkNames.length; ni++) {
      var network = getNetworkByName(networkDescriptions, networkNames[ni]);
      if (!network) {
        continue;
      }

      if (!networkLinks[cd.documentSelfLink]) {
        networkLinks[cd.documentSelfLink] = [];
      }

      networkLinks[cd.documentSelfLink].push(network.documentSelfLink);
    }
  }
  return networkLinks;
};

let getNetworkNamesOfContainer = function(cd) {
  var cdNetworks = [];
  if (cd.networks) {
    for (var key in cd.networks) {
      if (cd.networks.hasOwnProperty(key)) {
        cdNetworks.push(key.toLowerCase());
      }
    }
  } else if (cd.networkMode) {
    cdNetworks.push(cd.networkMode.toLowerCase());
  }

  return cdNetworks;
};

let updateContainersNetworks = function(attachContainersToNetworks, detachContainersToNetworks) {
  var networks = utils.getIn(this.getData(),
                               ['selectedItemDetails', 'templateDetails', 'listView', 'networks']);

  var containers = utils.getIn(this.getData(),
                               ['selectedItemDetails', 'templateDetails', 'listView', 'items']);

  var networksObj = {};
  for (let i = 0; i < networks.length; i++) {
    networksObj[networks[i].documentSelfLink] = networks[i];
  }

  var containersObj = {};
  for (let i = 0; i < containers.length; i++) {
    var container = containers[i].asMutable({deep: true});
    containersObj[container.documentSelfLink] = {
      networkMode: container.networkModel,
      networks: container.networks || {}
    };
  }

  var systemNetworkModes = [constants.NETWORK_MODES.BRIDGE.toLowerCase(),
                            constants.NETWORK_MODES.HOST.toLowerCase(),
                            constants.NETWORK_MODES.NONE.toLowerCase()];

  var containerPatches = {};
  var promises = [];

  attachContainersToNetworks.forEach((containerToNetworks) => {
    var network = networksObj[containerToNetworks.networkDescriptionLink];

    var patchObject = containersObj[containerToNetworks.containerDescriptionLink];
    if (systemNetworkModes.indexOf(network.name.toLowerCase()) !== -1) {
      patchObject.networkMode = network.name;
    } else {
      patchObject.networks[network.name] = {};
    }
    containerPatches[containerToNetworks.containerDescriptionLink] = patchObject;
  });

  detachContainersToNetworks.forEach((containerToNetworks) => {
    var network = networksObj[containerToNetworks.networkDescriptionLink];

    var patchObject = containersObj[containerToNetworks.containerDescriptionLink];

    if (systemNetworkModes.indexOf(network.name.toLowerCase()) !== -1) {
      patchObject.networkMode = '';
    } else {
      delete patchObject.networks[network.name];
    }

    containerPatches[containerToNetworks.containerDescriptionLink] = patchObject;
  });

  for (var link in containerPatches) {
    if (containerPatches.hasOwnProperty(link)) {
      promises.push(
       services.patchDocument(link, containerPatches[link]));
    }
  }

  Promise.all(promises).then((updatedDescriptions) => {
    var containerDefs = utils.getIn(this.getData(),
                  ['selectedItemDetails', 'templateDetails', 'listView', 'items']);
    if (containerDefs) {
      containerDefs = containerDefs.map((cd) => {
        for (var i = 0; i < updatedDescriptions.length; i++) {
          var updatedDescription = updatedDescriptions[i];
           if (cd.documentSelfLink === updatedDescription.documentSelfLink) {
            cd = cd.merge(updatedDescription);
          }
        }

        return cd;
      });

      updateNetworksAndLinks.call(this, containerDefs);

      this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'items'],
                    containerDefs);

      this.emitChange();
    }
  }).catch(this.onGenericEditError);
};

let updateNetworksAndLinks = function(containerDescriptions) {
  var networks = this.data.selectedItemDetails.templateDetails.listView.networks;
  networks = networks.asMutable();
  networks = getCompleteNetworkDescriptions(containerDescriptions, networks);
  var networkLinks = getNetworkLinks(containerDescriptions, networks);

  this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'networks'], networks);
  this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'networkLinks'],
                 networkLinks);
};

let TemplatesStore = Reflux.createStore({
  mixins: [ContextPanelStoreMixin, CrudStoreMixin],

  init: function() {
    RegistryStore.listen((registryData) => {
      if (this.data.registries) {
        this.setInData(['registries'], registryData);
        this.emitChange();
      }
    });

    if (utils.isApplicationEmbedded()) {
      services.loadGroups().then((groupsResult) => {
        let groups = Object.values(groupsResult);

        this.setInData(['groups'], groups);
        if (this.data.selectedItemDetails && this.data.selectedItemDetails.documentId) {
          this.setInData(['selectedItemDetails', 'groups'], groups);
        }
        this.emitChange();
      });
    } else {
      ResourceGroupsStore.listen((resourceGroupsData) => {
        let resourceGroups = resourceGroupsData.items;

        this.setInData(['groups'], resourceGroups);
        if (this.data.selectedItemDetails && this.data.selectedItemDetails.documentId) {
          this.setInData(['selectedItemDetails', 'groups'], resourceGroups);
        }

        this.emitChange();
      });
    }

    NotificationsStore.listen((notifications) => {
      this.setInData(['contextView', 'notifications', constants.CONTEXT_PANEL.REQUESTS],
        notifications.runningRequestItemsCount);

      this.setInData(['contextView', 'notifications', constants.CONTEXT_PANEL.EVENTLOGS],
        notifications.latestEventLogItemsCount);

      this.emitChange();
    });

    RequestsStore.listen((requestsData) => {
      if (this.isContextPanelActive(constants.CONTEXT_PANEL.REQUESTS)) {
        this.setActiveItemData(requestsData);
        this.emitChange();
      }
    });

    EventLogStore.listen((eventlogsData) => {
      if (this.isContextPanelActive(constants.CONTEXT_PANEL.EVENTLOGS)) {
        this.setActiveItemData(eventlogsData);
        this.emitChange();
      }
    });
  },

  listenables: [actions.TemplateActions, actions.RegistryActions, actions.ContainerActions,
                actions.TemplatesContextToolbarActions],

  onOpenTemplates: function(queryOptions, forceReload) {
    var currentTemplates = this.data.listView && this.data.listView.items;
    if (!forceReload && currentTemplates && currentTemplates !== constants.LOADING) {
      return;
    }

    this.setInData(['listView', 'queryOptions'], queryOptions);
    this.setInData(['listView', 'error'], null);
    this.setInData(['registries'], null);
    this.setInData(['importTemplate'], null);
    this.setInData(['selectedItem'], null);
    this.setInData(['selectedItemDetails'], null);
    this.setInData(['contextView'], {});


    if (utils.isApplicationEmbedded()) {
      services.loadGroups().then((groupsResult) => {
        this.setInData(['groups'], Object.values(groupsResult));
        this.emitChange();
      });
    } else {
      actions.ResourceGroupsActions.retrieveGroups();
    }

    var shouldLoadRecommended = !queryOptions
      || (Object.keys(queryOptions).length === 1 &&
          (queryOptions[constants.SEARCH_CATEGORY_PARAM]
                  === constants.TEMPLATES.SEARCH_CATEGORY.ALL
            || queryOptions[constants.SEARCH_CATEGORY_PARAM]
                  === constants.TEMPLATES.SEARCH_CATEGORY.IMAGES));
    if (shouldLoadRecommended) {

      loadRecommended.call(this);
    } else {

      searchImages.call(this, queryOptions);
    }
  },

  onOpenRegistries: function() {
    this.setInData(['registries'], RegistryStore.getData());
    this.emitChange();
  },

  onOpenToolbarEventLogs: function(highlightedItemLink) {
    actions.EventLogActions.openEventLog(highlightedItemLink);
    this.openToolbarItem(constants.CONTEXT_PANEL.EVENTLOGS, EventLogStore.getData());
  },

  onOpenContainerRequest: function(type, itemId) {
    var containerRequest = {
      documentId: itemId,
      selectedForEdit: false,
      selectedForRequest: true
    };

    if (type === constants.TEMPLATES.TYPES.IMAGE) {

      let calls = [];
      calls.push(services.loadDeploymentPolicies());

      if (utils.isApplicationEmbedded()) {
        calls.push(services.loadGroups());
      } else {
        actions.ResourceGroupsActions.retrieveGroups();
      }

      Promise.all(calls).then(([policies, groupsResult]) => {
        containerRequest.definitionInstance = {
          image: itemId,
          name: utils.getDocumentId(itemId),
          deploymentPolicies: policies
        };

        if (groupsResult) {
          containerRequest.groups = Object.values(groupsResult);
        }

        this.setInData(['selectedItem'], containerRequest);
        this.setInData(['selectedItemDetails'], containerRequest);

        this.emitChange();
      });
    }

    this.setInData(['selectedItem'], containerRequest);
    this.setInData(['selectedItemDetails'], containerRequest);

    this.emitChange();
  },

  onOpenTemplateDetails: function(type, itemId) {
    var detailsObject = {
      documentId: itemId,
      type: type,
      selectedForEdit: true,
      selectedForRequest: false
    };

    if (type === constants.TEMPLATES.TYPES.TEMPLATE) {
      detailsObject.templateDetails = {};
      detailsObject.templateDetails.listView = {};
      detailsObject.templateDetails.listView.itemsLoading = true;

      let calls = [];
      calls.push(services.loadContainerTemplate(itemId));

      if (utils.isApplicationEmbedded()) {
        calls.push(services.loadGroups());
      } else {
        actions.ResourceGroupsActions.retrieveGroups();
      }

      Promise.all(calls).then(([template, groupsResult]) => {

        if (groupsResult) {
          detailsObject.groups = Object.values(groupsResult);
        }

        var descriptionPromises = [];
        for (let i = 0; i < template.descriptionLinks.length; i++) {
          descriptionPromises.push(services.loadDocument(template.descriptionLinks[i]));
        }

        Promise.all(descriptionPromises).then((descriptions) => {
          var containerDescriptions = [];
          var networkDescriptions = [];
          for (let i = 0; i < descriptions.length; i++) {
            var desc = descriptions[i];
            if (desc.documentSelfLink.indexOf(links.CONTAINER_DESCRIPTIONS) !== -1) {
              containerDescriptions.push(desc);
            } else if (desc.documentSelfLink.indexOf(links.CONTAINER_NETWORK_DESCRIPTIONS)
                       !== -1) {
              networkDescriptions.push(desc);
            }
          }

          for (let i = 0; i < containerDescriptions.length; i++) {
            _enhanceContainerDescription(containerDescriptions[i], containerDescriptions);
          }

          networkDescriptions = getCompleteNetworkDescriptions(containerDescriptions,
                                                               networkDescriptions);
          var networkLinks = getNetworkLinks(containerDescriptions, networkDescriptions);

          detailsObject.templateDetails.name = template.name;
          detailsObject.templateDetails.documentSelfLink = template.documentSelfLink;
          detailsObject.templateDetails.listView.items = containerDescriptions;
          detailsObject.templateDetails.listView.itemsLoading = false;
          detailsObject.templateDetails.listView.networks = networkDescriptions;
          detailsObject.templateDetails.listView.networkLinks = networkLinks;
          this.setInData(['selectedItemDetails'], detailsObject);
          this.emitChange();
        });
      });
    }

    this.setInData(['selectedItem'], detailsObject);
    this.setInData(['selectedItemDetails'], detailsObject);
    this.emitChange();
  },

  onOpenAddNewContainerDefinition: function() {
    this.setInData(['selectedItemDetails', 'newContainerDefinition'], {});
    loadRecommended.call(this, true);
  },

  onOpenEditContainerDefinition: function(documentSelfLink) {

    services.loadDocument(documentSelfLink).then((containerDefinition) => {

      services.loadDeploymentPolicies().then((policies) => {
        containerDefinition.deploymentPolicies = policies;
        var networks = utils.getIn(this.getData(),
                               ['selectedItemDetails', 'templateDetails', 'listView',
                                'networks']) || [];
        containerDefinition.availableNetworks = getAllNetworkDescriptions(networks);

        this.setContainerDefinitionData(containerDefinition);

        this.emitChange();
      });
    }).catch(this.onGenericEditError);
  },

  onOpenAddNetwork: function(editDefinitionSelectedNetworks) {
    this.setInData(['selectedItemDetails', 'addNetwork'], {
      editDefinitionSelectedNetworks: editDefinitionSelectedNetworks
    });
    this.emitChange();
  },

  onCancelAddNetwork: function() {
    var cdSelector = this.selectFromData(['selectedItemDetails', 'editContainerDefinition',
                                          'definitionInstance']);
    var addNetworkSelector = this.selectFromData(['selectedItemDetails', 'addNetwork']);

    var editDefinitionSelectedNetworks = addNetworkSelector.getIn(
      ['editDefinitionSelectedNetworks']);
    if (cdSelector.get() && editDefinitionSelectedNetworks) {
      editDefinitionSelectedNetworks = editDefinitionSelectedNetworks.asMutable();
      delete editDefinitionSelectedNetworks[constants.NEW_ITEM_SYSTEM_VALUE];
      cdSelector.setIn('availableNetworks',
                       getAllNetworkDescriptions(editDefinitionSelectedNetworks));
      cdSelector.setIn('networks', editDefinitionSelectedNetworks);
    }
    addNetworkSelector.clear();

    this.emitChange();
  },

  onAddNetwork: function(templateId, network) {
    let networkDescription = {
      name: network.name,
      driver: 'overlay'
    };
    services.createNetworkDescription(networkDescription).then((createdDescription) => {

      services.loadContainerTemplate(templateId).then((template) => {

        template.descriptionLinks.push(createdDescription.documentSelfLink);

        return services.updateContainerTemplate(template);

      }).then(() => {

        if (this.data.selectedItemDetails &&
            this.data.selectedItemDetails.documentId === templateId) {

          var networks = utils.getIn(this.getData(),
                               ['selectedItemDetails', 'templateDetails', 'listView', 'networks']);
          networks = networks.asMutable();
          networks.push(createdDescription);
          this.setInData(
            ['selectedItemDetails', 'templateDetails', 'listView', 'networks'], networks);

          var cdSelector = this.selectFromData(['selectedItemDetails', 'editContainerDefinition',
                                          'definitionInstance']);
          var addNetworkSelector = this.selectFromData(['selectedItemDetails', 'addNetwork']);

          var editDefinitionSelectedNetworks = addNetworkSelector.getIn(
            ['editDefinitionSelectedNetworks']);
          if (cdSelector.get() && editDefinitionSelectedNetworks) {
            editDefinitionSelectedNetworks = editDefinitionSelectedNetworks.asMutable();
            delete editDefinitionSelectedNetworks[constants.NEW_ITEM_SYSTEM_VALUE];
            editDefinitionSelectedNetworks[createdDescription.name] = {};

            cdSelector.setIn('availableNetworks', getAllNetworkDescriptions(networks));
            cdSelector.setIn('networks', editDefinitionSelectedNetworks);
          }
          addNetworkSelector.clear();

          this.emitChange();
        }
      }).catch(this.onGenericEditError);
    }).catch(this.onGenericEditError);
  },

  onRemoveNetwork: function(templateId, network) {
    var networkDescirptionLink = network.documentSelfLink;

    var doDelete = function() {
      var containers = this.data.selectedItemDetails.templateDetails.listView.items;

      var networks = this.data.selectedItemDetails.templateDetails.listView.networks
        .filter((item) => {
          return item.documentSelfLink !== networkDescirptionLink;
        });

      var containersToDetach = [];
      containers.forEach((c) => {
        if (c.networkMode && c.networkMode === network.name) {
          containersToDetach.push({
            containerDescriptionLink: c.documentSelfLink,
            networkDescriptionLink: networkDescirptionLink
          });
        }
        if (c.networks && c.networks === network.name) {
          containersToDetach.push({
            containerDescriptionLink: c.documentSelfLink,
            networkDescriptionLink: networkDescirptionLink
          });
        }
      });
      var containersToAttach = [];
      updateContainersNetworks.call(this, containersToAttach, containersToDetach);

      this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'networks'],
                     networks);
      this.emitChange();
    };

    if (networkDescirptionLink.indexOf(SYSTEM_NETWORK_LINK) === 0) {
      doDelete.call(this);
      return;
    }

    services.deleteDocument(networkDescirptionLink).then(() => {
      return services.loadContainerTemplate(templateId);

    }).then((template) => {
      var index = template.descriptionLinks.indexOf(networkDescirptionLink);
      template.descriptionLinks.splice(index, 1);

      return services.updateContainerTemplate(template);

    }).then(() => {

      if (this.data.selectedItemDetails &&
          this.data.selectedItemDetails.documentId === templateId) {
        doDelete.call(this);
      }
    }).catch(this.onGenericEditError);
  },

  onAttachNetwork: function(containerDescriptionLink, networkDescriptionLink) {
    var containersToAttach = [{
      containerDescriptionLink: containerDescriptionLink,
      networkDescriptionLink: networkDescriptionLink
    }];
    var containersToDetach = [];
    updateContainersNetworks.call(this, containersToAttach, containersToDetach);
  },

  onDetachNetwork: function(containerDescriptionLink, networkDescriptionLink) {
    var containersToAttach = [];
    var containersToDetach = [{
      containerDescriptionLink: containerDescriptionLink,
      networkDescriptionLink: networkDescriptionLink
    }];
    updateContainersNetworks.call(this, containersToAttach, containersToDetach);
  },

  setContainerDefinitionData: function(containerDefinition) {
    var containerDefs = utils.getIn(this.getData(),
                    ['selectedItemDetails', 'templateDetails', 'listView', 'items']);
    _enhanceContainerDescription(containerDefinition, containerDefs);

    this.setInData(['selectedItemDetails', 'editContainerDefinition', 'definitionInstance'],
                      containerDefinition);
  },

  onIncreaseClusterSize: function(containerDefinition) {

    return this.modifyDescriptionClusterSize(containerDefinition, true);
  },

  onDecreaseClusterSize: function(containerDefinition) {

    return this.modifyDescriptionClusterSize(containerDefinition, false);
  },

  modifyDescriptionClusterSize: function(containerDefinition, increment) {
    var template = containerDefinition.asMutable({deep: true});

    if (increment) {
      if (template._cluster === 0) {
        template._cluster = 1;
      }
      template._cluster += 1;

    } else if (template._cluster > 1) { // decrement
      template._cluster -= 1;
    }

    services.updateContainerDescription(template).then((updatedDefinition) => {

      var listViewItems =
        this.data.selectedItemDetails.templateDetails.listView.items.asMutable({deep: true});

      for (var i = 0; i < listViewItems.length; i++) {
        if (listViewItems[i].documentSelfLink === updatedDefinition.documentSelfLink) {

          listViewItems[i]._cluster = updatedDefinition._cluster;
          break;
        }
      }

      this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'items'],
                     listViewItems);
      this.emitChange();

    }).catch(this.onGenericEditError);
  },

  onAddContainerDefinition: function(templateId, containerDefinition) {

    services.createContainerDescription(containerDefinition).then((createdDefinition) => {

      services.loadContainerTemplate(templateId).then((template) => {

        template.descriptionLinks.push(createdDefinition.documentSelfLink);

        return services.updateContainerTemplate(template);

      }).then(() => {

        if (this.data.selectedItemDetails &&
            this.data.selectedItemDetails.documentId === templateId) {

          var listViewItems = this.data.selectedItemDetails.templateDetails.listView.items
            .asMutable({deep: true});

          _enhanceContainerDescription(createdDefinition, listViewItems);
          listViewItems.push(createdDefinition);

          for (let i = 0; i < listViewItems.length; i++) {
            _enhanceContainerDescription(listViewItems[i], listViewItems);
          }

          updateNetworksAndLinks.call(this, listViewItems);

          this.setInData(['selectedItemDetails', 'newContainerDefinition'], null);
          this.setInData(['selectedItemDetails', 'editContainerDefinition'], null);
          this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'items'],
                         listViewItems);
          this.emitChange();
        }
      });
    });
  },

  onRemoveContainerDefinition: function(containerDefinition) {
    var templateId = this.data.selectedItemDetails.documentId;
    var containerDescriptionLink = containerDefinition.documentSelfLink;

    services.deleteDocument(containerDescriptionLink).then(() => {
      return services.loadContainerTemplate(templateId);

    }).then((template) => {
      var index = template.descriptionLinks.indexOf(containerDescriptionLink);
      template.descriptionLinks.splice(index, 1);

      return services.updateContainerTemplate(template);

    }).then(() => {

      if (this.data.selectedItemDetails &&
          this.data.selectedItemDetails.documentId === templateId) {

        var listViewItems = this.data.selectedItemDetails.templateDetails.listView.items
          .asMutable();
        var newListViewItems = [];
        for (var i = 0; i < listViewItems.length; i++) {
          if (listViewItems[i].documentSelfLink !== containerDescriptionLink) {
            newListViewItems.push(listViewItems[i]);
          }
        }

        updateNetworksAndLinks.call(this, newListViewItems);

        this.setInData(['selectedItemDetails', 'newContainerDefinition'], null);
        this.setInData(['selectedItemDetails', 'editContainerDefinition'], null);
        this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'items'],
                       newListViewItems);
        this.emitChange();
      }
    }).catch(this.onContainerDescriptionDeleteError);
  },

  onSaveContainerDefinition: function(templateId, containerDefinition) {
    services.updateContainerDescription(containerDefinition).then((updatedDefinition) => {
      if (this.data.selectedItemDetails &&
          this.data.selectedItemDetails.documentId === templateId) {

        var listViewItems = this.data.selectedItemDetails.templateDetails.listView.items
          .asMutable({'deep': true});

        for (var i = 0, len = listViewItems.length; i < len; i += 1) {
          if (listViewItems[i].documentSelfLink === updatedDefinition.documentSelfLink) {
            listViewItems[i] = updatedDefinition;
          }
        }

        for (i = 0, len = listViewItems.length; i < len; i += 1) {
          _enhanceContainerDescription(listViewItems[i], listViewItems);
        }

        updateNetworksAndLinks.call(this, listViewItems);

        this.setInData(['selectedItemDetails', 'newContainerDefinition'], null);
        this.setInData(['selectedItemDetails', 'editContainerDefinition'], null);
        this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'items'],
                       listViewItems);
        this.emitChange();
      }
    }).catch(this.onGenericEditError);
  },

  onCancelContainerDefinitionEdit: function() {
    this.setInData(['selectedItemDetails', 'newContainerDefinition'], null);
    this.setInData(['selectedItemDetails', 'editContainerDefinition'], null);
    this.emitChange();
  },

  onResetContainerDefinitionEdit: function() {
    if (this.data.selectedItemDetails.newContainerDefinition &&
        this.data.selectedItemDetails.newContainerDefinition.definitionInstance) {
      this.setInData(['selectedItemDetails', 'newContainerDefinition',
                                  'definitionInstance'], null);
    } else if (this.data.selectedItemDetails.editContainerDefinition) {
      this.setInData(['selectedItemDetails', 'editContainerDefinition'], null);
    } else {
      throw new Error('Requested to cancel container definition edit at unexpected state');
    }

    this.emitChange();
  },

  onSearchImagesForContainerDefinition: function(queryOptions) {
    var queryObjectsPath = ['selectedItemDetails', 'newContainerDefinition', 'listView',
                            'queryOptions'];

    if (queryOptions && !$.isEmptyObject(queryOptions)) {
      this.setInData(queryObjectsPath, queryOptions);
      searchImages.call(this, queryOptions, true, true);
    } else {
      this.setInData(queryObjectsPath, null);
      loadRecommended.call(this, true);
    }
  },

  onSelectImageForContainerDescription: function(imageId) {
    var definitionInstance = {
      image: imageId,
      name: utils.getDocumentId(imageId)
    };
    var containerDefs = utils.getIn(this.getData(),
        ['selectedItemDetails', 'templateDetails', 'listView', 'items']);
    _enhanceContainerDescription(definitionInstance, containerDefs);
    this.setInData(['selectedItemDetails', 'newContainerDefinition',
                    'definitionInstance'], definitionInstance);
    this.emitChange();
  },

  onCreateContainer: function(type, itemId, group) {
    var items = this.data.listView.items.asMutable();
    for (var i = 0; i < items.length; i++) {
      if (items[i].documentId === itemId) {
        items[i] = utils.setIn(items[i], ['provisioning'], true);
      }
    }

    this.setInData(['listView', 'items'], items);
    this.emitChange();

    var onContainerCreated = (request) => {
      var items = this.data.listView.items.asMutable();
      for (var i = 0; i < items.length; i++) {
        if (items[i].documentId === itemId) {
          items[i] = utils.setIn(items[i], ['provisioning'], false);
        }
      }

      this.setInData(['listView', 'items'], items);
      this.emitChange();

      this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
      actions.RequestsActions.requestCreated(request);
    };

    if (type === constants.TEMPLATES.TYPES.IMAGE) {
      var name = utils.getDocumentId(itemId);

      var containerDescription = {
        image: itemId,
        name: name,
        publishAll: true
      };

      services.createContainer(containerDescription, group).then((request) => {
        onContainerCreated(request);

      }).catch(this.onGenericCreateError);

    } else if (type === constants.TEMPLATES.TYPES.TEMPLATE) {

      services.createMultiContainerFromTemplate(itemId, group).then((request) => {
        onContainerCreated(request);

      }).catch(this.onGenericCreateError);
    }
  },

  onCreateContainerWithDetails: function(containerDescription, group) {
    services.createContainer(containerDescription, group).then((request) => {
      navigateTemplatesAndOpenRequests.call(this, request);
    }).catch(this.onGenericCreateError);
  },

  onCreateContainerTemplate: function(containerDescription) {
    services.createContainerTemplate(containerDescription).then((containerTemplate) => {
      var documentId = utils.getDocumentId(containerTemplate.documentSelfLink);
      actions.NavigationActions.openTemplateDetails(constants.TEMPLATES.TYPES.TEMPLATE, documentId);

    }).catch(this.onGenericCreateError);
  },

  onRemoveTemplate: function(templateId) {

    services.loadContainerTemplate(templateId).then((template) => {

      var containerDescriptionsToDeletePromises = [];
      for (let i = 0; i < template.descriptionLinks.length; i++) {
        containerDescriptionsToDeletePromises.push(
          services.deleteDocument(template.descriptionLinks[i]));
      }

      // Delete all container descriptions related to the template to be removed
      Promise.all(containerDescriptionsToDeletePromises).then(() => {
        // Remove the template itself
        services.removeContainerTemplate(templateId).then(() => {
          let queryOptions = this.selectFromData(['listView', 'queryOptions']).get();
          // Refresh view
          this.onOpenTemplates(queryOptions, true);

        }).catch(this.onTemplateRemoveError);
      }).catch(this.onContainerDescriptionDeleteError);
    });
  },

  onSaveTemplateName: function(templateId, templateName) {
    let templateLinkPrefix = links.COMPOSITE_DESCRIPTIONS + '/';

    let templateDocumentSelfLink = templateId;
    if (templateId.indexOf(templateLinkPrefix) === -1) {
      templateDocumentSelfLink = templateLinkPrefix + templateId;
    }

    let updateNameTemplateObj = {
      documentSelfLink: templateDocumentSelfLink,
      name: templateName
    };

    services.updateContainerTemplate(updateNameTemplateObj).then((template) => {
      // in case nothing is updated, the template is undefined
      if (template) {
        let templateId = template.documentSelfLink.substring(templateLinkPrefix.length);

        let selectedItemDetails = utils.getIn(this.getData(), ['selectedItemDetails']);
        if (selectedItemDetails && (selectedItemDetails.documentId === templateId)) {

          let templateDetails = utils.getIn(selectedItemDetails, ['templateDetails']);
          if (templateDetails) {

            this.setInData(['selectedItemDetails', 'templateDetails', 'name'], template.name);
            this.emitChange();
          }
        }
      }
    }).catch(this.onGenericEditError);
  },

  onCopyTemplate: function(type, template, group) {
    services.copyContainerTemplate(template).then((result) => {
      actions.ContainerActions.createContainer(type,
                                              utils.getDocumentId(result.documentSelfLink), group);
    }).catch(this.onGenericCreateError);
  },

  onPublishTemplate: function(templateId) {
   if (this.data.selectedItemDetails) {
      // we are in template details view
      this.setInData(['selectedItemDetails', 'alert'], null);
      this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'itemsLoading'], true);
    } else {
      // we are in main templates view
      this.setInData(['listView', 'itemsLoading'], true);
    }
    this.emitChange();

    let templateLinkPrefix = links.COMPOSITE_DESCRIPTIONS + '/';

    let templateDocumentSelfLink = templateId;
    if (templateId.indexOf(templateLinkPrefix) === -1) {
      templateDocumentSelfLink = templateLinkPrefix + templateId;
    }

    let publishedTemplateObj = {
      documentSelfLink: templateDocumentSelfLink,
      status: constants.TEMPLATES.STATUS.PUBLISHED
    };

    services.updateContainerTemplate(publishedTemplateObj).then(() => {
      handlePublishTemplate.call(this, templateDocumentSelfLink, {
        type: 'success',
        message: i18n.t('app.template.publish.success')
      });
    }).catch((e) => {
      let errorMessage = utils.getErrorMessage(e);
      console.log(errorMessage);
      if (errorMessage && errorMessage._generic) {
        errorMessage = errorMessage._generic;
      } else {
        errorMessage = i18n.t('app.template.publish.fail');
      }

      handlePublishTemplate.call(this, templateDocumentSelfLink, {
        type: 'fail',
        message: errorMessage
      });
    });
  },

  onOpenImportTemplate: function() {
    this.setInData(['importTemplate', 'isImportingTemplate'], false);
    this.emitChange();
  },

  onImportTemplate: function(templateContent) {
    this.setInData(['importTemplate', 'error'], null);
    this.setInData(['importTemplate', 'isImportingTemplate'], true);
    this.emitChange();

    services.importContainerTemplate(templateContent).then((templateSelfLink) => {
      this.setInData(['importTemplate'], null);
      this.emitChange();

      var documentId = utils.getDocumentId(templateSelfLink);
      actions.NavigationActions.openTemplateDetails(constants.TEMPLATES.TYPES.TEMPLATE, documentId);
    }).catch(this.onImportTemplateError);
  },

  onImportTemplateError: function(e) {
    var validationErrors = utils.getValidationErrors(e);
    this.setInData(['importTemplate', 'error'], validationErrors);
    this.setInData(['importTemplate', 'isImportingTemplate'], false);
    this.emitChange();
  },

  onOpenToolbarRequests: function() {
    actions.RequestsActions.openRequests();
    this.openToolbarItem(constants.CONTEXT_PANEL.REQUESTS, RequestsStore.getData());
  },

  onCloseToolbar: function() {
    this.closeToolbar();
  },

  addGenericEditError: function(e) {
    this.setInData(['selectedItemDetails', 'editContainerDefinition',
                        'definitionInstance', 'error'], utils.getErrorMessage(e));
  },

  onGenericEditError: function(e) {
    var validationErrors = utils.getValidationErrors(e);
    this.setInData(['selectedItemDetails', 'definitionInstance', 'error'], validationErrors);
    this.emitChange();
  },

  onGenericCreateError: function(e) {
    this.onGenericEditError(e);
  },

  onTemplateRemoveError: function(e) {
    this.setInData(['listView', 'error'], utils.getErrorMessage(e));
    this.emitChange();
  },

  onContainerDescriptionDeleteError: function(e) {
    this.setInData(['selectedItemDetails', 'templateDetails', 'listView', 'error'],
                        utils.getErrorMessage(e));
    this.emitChange();
  }
});

export default TemplatesStore;

