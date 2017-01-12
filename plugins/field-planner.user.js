// ==UserScript==
// @id             iitc-plugin-field-planner@cyblocker
// @name           IITC plugin: Field Planner
// @category       Info
// @version        0.0.0.@@DATETIMEVERSION@@
// @namespace      https://github.com/jonatkins/ingress-intel-total-conversion
// @updateURL      @@UPDATEURL@@
// @downloadURL    @@DOWNLOADURL@@
// @description    [@@BUILDNAME@@-@@BUILDDATE@@] Help you build fields among a selected set of portals.
// @include        https://*.ingress.com/intel*
// @include        http://*.ingress.com/intel*
// @match          https://*.ingress.com/intel*
// @match          http://*.ingress.com/intel*
// @include        https://*.ingress.com/mission/*
// @include        http://*.ingress.com/mission/*
// @match          https://*.ingress.com/mission/*
// @match          http://*.ingress.com/mission/*
// @grant          none
// ==/UserScript==

@@PLUGINSTART@@

// PLUGIN START ////////////////////////////////////////////////////////

// use own namespace for plugin
window.plugin.fieldPlanner = function() {};

// we prepend a hash sign (#) in front of the player name in storage in order to prevent accessing a pre-defined property
// (like constructor, __defineGetter__, etc.

window.plugin.fieldPlanner.setupCallback = function() {
  $('#toolbox').append(' <a onclick="window.plugin.fieldPlanner.showSet()" title="Show selected portals for plan">Field Planner</a><input type="checkbox" id="plannerCheckbox" onclick="window.plugin.fieldPlanner.modifyList()">');
  addHook('portalSelected', window.plugin.fieldPlanner.addButtonGenerate);
  addHook('portalDetailsUpdated', window.plugin.fieldPlanner.getData);
};

var fieldPlannerPortalSet = new Set();
var fieldPlannerCurrentPortal = null;
var fieldPlannerPortalInfo = new Object();

window.plugin.fieldPlanner.addButtonGenerate = function(data){
    console.log(data);
    var portal = fieldPlannerPortalInfo[data.selectedPortalGuid];
    console.log(portal);
    fieldPlannerCurrentPortal = data.selectedPortalGuid;
    if(portal && fieldPlannerPortalSet.has(portal.guid) == true){
        console.log('checked');
        $("#plannerCheckbox").prop('checked', true);;
    }
    else{
        console.log('not checked');
        $("#plannerCheckbox").prop('checked', false);;
    }
};

window.plugin.fieldPlanner.getData = function(data){
    var portal = window.plugin.fieldPlanner.wrapPortalData(data);
    fieldPlannerPortalInfo[portal.guid] = portal;
}

window.plugin.fieldPlanner.modifyList = function(){
    var portal = fieldPlannerPortalInfo[fieldPlannerCurrentPortal];
    if($("#plannerCheckbox").is(":checked") && !fieldPlannerPortalSet.has(portal.guid))
    {
        fieldPlannerPortalSet.add(portal.guid);
    }
    else if(!$("#plannerCheckbox").is(":checked") && fieldPlannerPortalSet.has(portal.guid))
    {
        fieldPlannerPortalSet.delete(portal.guid);
    }
    console.log(fieldPlannerPortalSet);
};

window.plugin.fieldPlanner.wrapPortalData = function(data){
    //console.log(data);
    var portal = {
        guid: data.guid,
        latE6: data.portalData.latE6,
        lngE6: data.portalData.lngE6,
        name: data.portalData.title
    };
    //console.log(portal);
    return portal;
};

window.plugin.fieldPlanner.showSet = function(){
    html = "";
    fieldPlannerPortalSet.forEach(function(guid){
        html += '<a onclick="window.plugin.fieldPlanner.delete("' + guid + '",this)" title="Delete this portal from Field Planner">' + fieldPlannerPortalInfo[guid].name + '</a><br/>';
    })
    dialog({
        title: 'Field Planner',
        html: html,
        width: 400,
        position: {my: 'right center', at: 'center-60 center', of: window, collision: 'fit'}
  });
}

window.plugin.fieldPlanner.delete = function(guid,e){
    e.remove();
    fieldPlannerPortalSet.delete(guid);
}

var setup =  function() {
  window.plugin.fieldPlanner.setupCallback();
};

// PLUGIN END //////////////////////////////////////////////////////////

@@PLUGINEND@@