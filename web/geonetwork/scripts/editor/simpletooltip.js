/* Handle tooltips in a less complicated manner than tooltip-manager.js 
 * These simple click-on-tips are used by the metadata viewer/editor - 
 * simpletooltips have the prefix stip and use a manually defined onclick 
 * event to call the toolTip function that reads the tip from the server */

function toolTip(spanId)
{
	elem = $(spanId);
	if (elem.childElements().length == 0) {
		// cant use spanId, IE barfs
		var tokens = elem.getAttribute('id').split('|'); 
		var schema = tokens[0].substring(5); // remove stip. 
		var name   = tokens[1];
	
		var request = str.substitute(toolTipRequestTemp, { SCHEMA:schema, NAME:name });
		
		ker.send('xml.schema.info', request, ker.wrap(this, function(xmlRes) {
	
			if (xmlRes.nodeName == 'error') {
				ker.showError('Cannot get tooltips from server', xmlRes);	
			} else {
				var htmlTip= getHtmlTip(xmlRes.getElementsByTagName('element')[0]);
				tip = document.createElement('span');
				tip.className     = 'toolTipOverlay';
				tip.innerHTML     = htmlTip;
				elem.appendChild(tip);
			}
		}));	
	} else { 
		childs = elem.childElements();
		childs[0].toggle();
	}
}

//=========================================================================

function getHtmlTip(node)
{
	var err = node.getAttribute('error');
	
	if (err != null)
	{
		var temp = toolTipErrorTemp;
		var msg  = 'ERROR : '+err;
		var data = { ERROR : msg };
		
		return str.substitute(toolTipErrorTemp, data);
	}
	else
	{
		var temp = toolTipTemp;
		var label= xml.evalXPath(node, 'label');
		var descr= xml.evalXPath(node, 'description');
		var cond = xml.evalXPath(node, 'condition');
		
		if (cond == null)
			cond = '';
			
		var data = { LABEL: label, DESCRIPTION : descr, CONDITION : cond };
		
		return str.substitute(toolTipTemp, data);
	}
}

//=========================================================================

var toolTipRequestTemp =
'<request xmlns:gmd="http://www.isotc211.org/2005/gmd"'+
'         xmlns:gts="http://www.isotc211.org/2005/gts"'+ 
'         xmlns:srv="http://www.isotc211.org/2005/srv"'+ 
'         xmlns:gml="http://www.opengis.net/gml"'+
'         xmlns:gco="http://www.isotc211.org/2005/gco"'+
'         xmlns:dct="http://purl.org/dc/terms/"'+
'         xmlns:dc ="http://purl.org/dc/elements/1.1/">'+
'   <element schema="{SCHEMA}" name="{NAME}"/>'+
'</request>';

//=====================================================================================

var toolTipTemp=
'   <b>{LABEL}</b>'+
'   <br>'+
'   {DESCRIPTION}'+
'   <br>'+
'   <font color="#C00000">{CONDITION}</font>';

//=====================================================================================

var toolTipErrorTemp=
'   <font color="#C00000">{ERROR}</font>';

//=====================================================================================