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

import ContainerDetailsVue from 'ContainerDetailsVue';
import Component from 'components/common/Component';
import MaximizableBehaviour from 'components/common/MaximizableBehaviour'; //eslint-disable-line
import VueToolbarActionButton from 'components/common/VueToolbarActionButton'; //eslint-disable-line
import ContainerProperties from 'components/containers/ContainerProperties'; //eslint-disable-line
import ContainerStats from 'components/containers/ContainerStats'; //eslint-disable-line
import ActionConfirmationSupportMixin from 'components/common/ActionConfirmationSupportMixin'; //eslint-disable-line
import { ContainerActions } from 'actions/Actions';
import constants from 'core/constants';
import utils from 'core/utils';
import modal from 'core/modal';
import ContainerShellTemplate from 'ContainerShellTemplate';

const REFRESH_STATS_TIMEOUT = 60000;
const REFRESH_LOGS_TIMEOUT = 5000;
const START_REFRESH_POLLING_DELAY = 2000;

var ContainerDetailsVueComponent = Vue.extend({
  template: ContainerDetailsVue,
  data: function() {
    return {
      logsSinceDurations: constants.CONTAINERS.LOGS.SINCE_DURATIONS
    };
  },

  props: {
    model: { required: true }
  },

  computed: {
    hasOperationError: function() {
      return this.model.operationFailure && (this.model.operationFailure != null);
    },
    hasGeneralError: function() {
      return this.model.error && this.model.error._generic;
    },
    generalError: function() {
      return this.hasGeneralError ? this.model.error._generic : '';
    }
  },

  mixins: [ActionConfirmationSupportMixin],

  attached: function() {
    this.modelUnwatch = this.$watch('model', this.updateData, {immediate: true});
  },

  detached: function() {
    stopRefreshPolling.call(this);
    clearTimeout(this.initialLogsAndStatsTimeout);
    this.modelUnwatch();
  },

  methods: {
    getContainer: function() {
      return this.model.instance;
    },

    getContainerId: function() {
      return this.getContainer().documentId;
    },

    updateData: function(newData, oldData) {
      if (newData.instance && newData.instance.powerState === constants.CONTAINERS.STATES.RUNNING) {
        if (!this.startRefreshPollingTimeout) {
          this.startRefreshPollingTimeout = setTimeout(
            () => maybeStartRefreshPolling.call(this), START_REFRESH_POLLING_DELAY);
        }
      } else {
        stopRefreshPolling.call(this);
      }

      if (!oldData || oldData.documentId !== newData.documentId) {
        clearTimeout(this.initialLogsAndStatsTimeout);
        this.initialLogsAndStatsTimeout = setTimeout(() => {
          ContainerActions.refreshContainerStats();
          ContainerActions.refreshContainerLogs();
        }, START_REFRESH_POLLING_DELAY);
      }

      var newShellData = newData.shell;
      var oldShellData = oldData && oldData.shell;
      if (newShellData !== oldShellData) {
        if (!newShellData) {
          this.$shell.find('iframe').remove();
          modal.hide(this.$shell);
          this.$shell = null;
        } else if (!this.$shell) {
          this.$shell = $(ContainerShellTemplate({
            shellUrl: newShellData.shellUri,
            newTabEnabled: true
          }));

          var $iframe = this.$shell.find('iframe');

          this.$shell.on('click', '.open-new-tab', (e) => {
            e.stopImmediatePropagation();
            e.preventDefault();

            var iframeWindow = $iframe[0].contentWindow;
            // TODO: once we have the shellinabox served from the same domain as our app,
            // we could re-use the session like so
            var sessionId = iframeWindow.shellinabox && iframeWindow.shellinabox.session;
            var url = $iframe.attr('src');
            if (sessionId) {
              url += '#' + sessionId;
            }
            var newWindow = window.open(url);
            if (newWindow) {
              ContainerActions.closeShell();
            } else {
              // Probably browser is blocking a popup
            }
          });

          this.$shell.on('click', '.close-button', (e) => {
            e.stopImmediatePropagation();
            e.preventDefault();

            ContainerActions.closeShell();
          });

          modal.show(this.$shell);
        }
      }
    },

    containerStatusDisplay: utils.containerStatusDisplay,

    onLogsSinceChange: function(event) {
      var sinceDuration = $(event.target).val();
      ContainerActions.changeLogsSinceDuration(sinceDuration);
    },

    cloneContainer: function($event) {
      $event.stopPropagation();
      $event.preventDefault();
      // No backend support yet, icon-name='copy'
    },

    rebootContainer: function($event) {
      $event.stopPropagation();
      $event.preventDefault();
      // No backend support yet, icon-name='refresh'
    },

    startContainer: function($event) {
      $event.stopPropagation();
      $event.preventDefault();

      ContainerActions.startContainerDetails(this.getContainerId());
    },

    stopContainer: function($event) {
      $event.stopPropagation();
      $event.preventDefault();

      ContainerActions.stopContainerDetails(this.getContainerId());
    },
    handleConfirmation: function(actionName) {

      if (actionName === 'removeContainer') {
        ContainerActions.removeContainerDetails(this.getContainerId());
      }
    },
    openShell: function($event) {
      $event.stopPropagation();
      $event.preventDefault();

      ContainerActions.openShell(this.model.documentId);
    },

    getOperationInProgress: function() {
      return this.model.operationInProgress;
    },

    hasOperationInProgress: function() {
      var op = this.getOperationInProgress();

      return op && (op != null);
    },

    operationSupported: function(op) {
      return utils.operationSupported(op, this.getContainer());
    },

    refresh: function() {
      ContainerActions.refreshContainer();
    },

    exposedServicesDescription: function(i18nKey) {
      var exposedService = this.model.exposedService;
      var mappings = exposedService.hostname;
      for (var key in exposedService.addressConfigs) {
        if (!exposedService.addressConfigs.hasOwnProperty(key)) {
          continue;
        }
        mappings += ' ' + utils.getURLParts(exposedService.addressConfigs[key].address).host;
      }

      var input = '<textarea onclick="this.setSelectionRange(0, ' + mappings.length +
          ')" readonly="readonly" >' + mappings + '</textarea>';

      return i18n.t(i18nKey, {hostMappings: input});
    }
  }
});

var maybeStartRefreshPolling = function() {
  if (!this.refreshStatsInterval) {
    this.refreshStatsInterval = setInterval(() => {
      ContainerActions.refreshContainerStats();
    }, REFRESH_STATS_TIMEOUT);
  }

  if (!this.refreshLogsInterval) {
    this.refreshLogsInterval = setInterval(() => {
      ContainerActions.refreshContainerLogs();
    }, REFRESH_LOGS_TIMEOUT);
  }
};

var stopRefreshPolling = function() {
  clearTimeout(this.startRefreshPollingTimeout);
  this.startRefreshPollingTimeout = null;

  clearInterval(this.refreshStatsInterval);
  this.refreshStatsInterval = null;

  clearInterval(this.refreshLogsInterval);
  this.refreshLogsInterval = null;
};

Vue.component('container-details', ContainerDetailsVueComponent);

class ContainerDetails extends Component {
  constructor() {
    super();
    this.$el = $('<div>').append('<container-details v-bind:model="currentModel">');
  }

  getEl() {
    return this.$el;
  }

  attached() {
    this.vue = new Vue({
      el: this.$el[0],
      data: {
        currentModel: {}
      }
    });
  }

  detached() {
    if (this.vue) {
      this.vue.$destroy();
      this.vue = null;
    }
  }

  setData(data) {
    Vue.set(this.vue, 'currentModel', data);
  }
}

export default ContainerDetails;
