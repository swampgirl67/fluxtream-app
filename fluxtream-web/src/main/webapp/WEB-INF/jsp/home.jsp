<%@ page pageEncoding="utf-8" contentType="text/html; charset=UTF-8"%><%@ taglib
	uri="http://granule.com/tags" prefix="g"
%><%@ page import="org.fluxtream.core.auth.AuthHelper"
%><%@ page import="org.fluxtream.core.domain.Guest"
%>
<%@ page import="java.util.List" %>
<%
    Boolean tracker = (Boolean)request.getAttribute("tracker");
    Boolean intercom = (Boolean)request.getAttribute("intercom");
    Boolean useMinifiedJs = (Boolean)request.getAttribute("useMinifiedJs");
    List<Guest> trustedBuddies = (List<Guest>)request.getAttribute("trustedBuddies");
    List<Guest> trustingBuddies = (List<Guest>)request.getAttribute("trustingBuddies");%><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
<meta name="apple-mobile-web-app-capable" content="yes" />
<meta name="apple-mobile-web-app-status-bar-style"
	content="black-translucent" />
<meta name="viewport" content="width=device-width">
<title>Fluxtream</title>
<meta name="description" content="">
<meta name="author" content="">

<link rel="stylesheet" href="/static/css/bootstrap-2.3.2.min.css">
<link rel="stylesheet"
      href="/static/css/bootstrap-responsive-2.3.2.min.css">
<g:compress>
	<link rel="stylesheet" href="/css/flx.css">
	<link rel="stylesheet" href="/css/bodytrack.css">
	<link rel="stylesheet" href="/css/datepicker.css">
    <link rel="stylesheet" href="/static/css/jquery-ui/jquery-ui-1.11.0.min.css">
    <link rel="stylesheet" href="/static/css/jquery-ui/jquery-ui-1.11.0.structure.min.css">
    <link rel="stylesheet" href="/static/css/jquery-ui-bootstrap/jquery.ui.theme.css">
	<link rel="stylesheet"
		href="/static/css/jquery-colorPicker/jquery.colorPicker.css">
	<link rel="stylesheet" href="/static/css/msdropdown/dd.css">
	<link rel="stylesheet" href="/static/css/tagedit/css/jquery.tagedit.css">
</g:compress>

<link rel="stylesheet" href="/static/css/font-awesome-3.2.1.css">

<script
	src="https://maps-api-ssl.google.com/maps/api/js?libraries=geometry,visualization&v=3&sensor=false"
	type="text/javascript"></script>
    <script src="/static/js/hogan-2.0.0.js"></script>

<link rel="shortcut icon" href="/favicon.ico">

</head>

<body>

	<div id="content">

		<div class="navbar">
			<div class="navbar-inner">
				<div class="container-fluid">
					<a class="btn btn-navbar" data-toggle="collapse-custom"
						data-target=".nav-collapse"> <span class="icon-bar"></span> <span
						class="icon-bar"></span> <span class="icon-bar"></span>
					</a>
                    <a class="brand" href="javascript:App.renderApp(App.state.defaultApp)"><img
                            src="/${release}/images/header-logo-v4.png" width=94 height=20/></a>
					<div class="nav-collapse">
						<%--<form class="navbar-search" action="javascript:App.search()">--%>
							<%--<input onkeypress="if(event.which==13) App.search()" autocorrect="off" autocapitalize="off" type="text"--%>
								<%--class="search-query" placeholder="Search">--%>
						<%--</form>--%>

                            <ul class="nav" id="appsMenuWrapper">
                            <li><div class="btn-group" id="apps-menu"
                                     data-toggle="buttons-radio"></div></li>
                            </ul>
                            <ul class="nav pull-right">
                                <li class="divider-vertical"></li>
                                <li class="dropdown" id="helpDropdownToggle" data-container="body">
                                    <a href="javascript:void(0)" class="dropdown-toggle" data-toggle="dropdown">Help <i class="icon-lightbulb icon-large"></i></a>
                                    <ul class="dropdown-menu">
                                        <li><a href="javascript:App.quickStart();">Quick Start</a></li>
                                        <li><a href="javascript:App.privacyPolicy();">Privacy Policy</a></li>
                                    </ul>
                                </li>
                                <li class="dropdown" id="buddiesDropdownToggle" data-container="body">
                                    <a href="javascript:void(0)" class="dropdown-toggle" data-toggle="dropdown">Buddies</a>
                                    <ul class="dropdown-menu">
                                        <li><a class="disabled" style="font-variant: small-caps">they share data with you</a></li><%
                                        if (trustingBuddies.size()>0) {
                                            for (Guest guest : trustingBuddies) {%>
                                        <li><a onclick="if (typeof(ga)!='undefined') {ga('send', 'event', 'menuitem', 'click', 'viewBuddyData', 1);}" href="javascript:App.as('<%=guest.getId()%>')"><%=guest.getGuestName()%><i class="buddiesmenu-icon icon-eye-open pull-right"></i></a></li>
                                            <%  } %>
                                        <li class="divider backtomydata"></li>
                                        <li class="backtomydata"><a onclick="if (typeof(ga)!='undefined') {ga('send', 'event', 'menuitem', 'click', 'viewMyData', 1);}" href="javascript:App.as(<%=AuthHelper.getGuestId()%>)">Back to My data</a></li>
                                        <% } %>
                                        <li class="divider trustedbuddies"></li>
                                        <li class="trustedbuddies"><a class="disabled" style="font-variant: small-caps">you share data with them</a></li><%
                                        if (trustedBuddies.size()>0) {
                                            for (Guest guest : trustedBuddies) {%>
                                        <li class="trustedbuddies"><a onclick="if (typeof(ga)!='undefined') {ga('send', 'event', 'menuitem', 'click', 'chatWithBuddy', 1);}" href="javascript:App.reachOutTo('<%=guest.getId()%>')"><%=guest.getGuestName()%><i class="buddiesmenu-icon icon-comment pull-right"></i></a></li>
                                            <%  } %>
                                        <% } else { %>
                                        <li class="trustedbuddies"><a class="disabled">(empty list)</a></li>
                                        <% }%>

                                    </ul>
                                </li>
                                <li class="dropdown" id="connectorsDropdownToggle" data-container="body">
                                    <a href="javascript:void(0)" class="dropdown-toggle" onclick="$('#connectorsDropdownToggle').popover('destroy');"
                                                        data-toggle="dropdown">Connectors
                                    <i class="icon-random icon-large"></i> <b class="caret"></b></a>
                                    <ul class="dropdown-menu">
                                        <li><a id="addConnectorLink"><i class="mainmenu-icon icon-plus icon-large pull-right"></i>Add</a></li>
                                        <li id="manageConnectorsMenuItem"><a href="javascript:App.manageConnectors()"><i class="mainmenu-icon icon-list icon-large pull-right"></i>Manage</a></li>
                                    </ul></li>
                                <li class="divider-vertical"></li>
							<li class="dropdown"><a href="javascript:void(0)" class="dropdown-toggle" onclick="$('#connectorsDropdownToggle').popover('destroy');" id="loggedInUser" data-toggle="dropdown" self="<%=request.getAttribute("fullname")%>"></a>
								<ul class="dropdown-menu">
									<li><a href="javascript:App.settings()"><i class="mainmenu-icon icon-cog icon-large pull-right"></i>Settings</a></li>
                                    <%--<li><a href="javascript:App.addresses()"><i class="mainmenu-icon icon-home icon-large pull-right"></i>Addresses</a></li>--%>
									<li id="coachingDivider" class="divider"></li>
                                    <li><a href="javascript:App.sharingDialog.show();if (typeof(ga)!='undefined') {ga('send', 'event', 'menuitem', 'click', 'sharingDialog', 1);}"><i class="mainmenu-icon icon-share icon-large pull-right"></i><span style="margin-right:2em">Share your data...</span></a></li>
                                    <li class="divider"></li>
									<li><a href="/logout" onclick="if (typeof(ga)!='undefined') {ga('send', 'event', 'menuitem', 'click', 'logout', 1);}"><i class="mainmenu-icon icon-off icon-large pull-right"></i>Logout</a></li>
								</ul></li>
                            </ul>
					</div>
				</div>
			</div>
		</div>

		<div id="applications" class="container-fluid">

			<div id="notifications" style="display:none">
			</div>

			<!-- here is where fluxtream apps go -->

		</div>

    </div>

    <jsp:include page="footer.jsp" />

	<%--<script--%>
		<%--src="//ajax.googleapis.com/ajax/libs/jquery/1.10.2/jquery.min.js"></script>--%>
	<script>
		window.jQuery
				|| document
						.write('<script src="/static/js/jquery-1.10.2.min.js"><\/script>')
	</script>
    <% if (tracker) { try{%>
        <jsp:include page="tracker.jsp" />
    <%} catch(Throwable t){} } %>
    <% if (intercom) { try{%>
        <jsp:include page="intercom.jsp" />
    <%} catch(Throwable t){} } %>

    <script src="/static/js/bootstrap-2.3.2.min.js"></script>
    <script src="/static/js/jquery-ui-1.10.3.custom.min.js"></script>
    <script src="/static/js/jquery.ui.sortable-1.8.2-bt-1.0.1.js"></script>
    <script src="/static/tiny_mce-3.5b1/jquery.tinymce.js"></script>
    <script src="/static/js/json2-2011.10.19.js"></script>
    <script src="/static/js/jquery.autoGrowInput-1.0.0.js"></script>
    <script src="/static/js/jquery.colorPicker-2012.01.27.js"></script>
    <script src="/static/js/jquery.dd-2.37.5-uncompressed.js"></script>
    <script src="/static/js/jquery.tagedit-1.2.1.js"></script>
    <script src="/static/js/jquery.shorten-1.0.0.js"></script>
    <script src="/static/js/raphael-2.1.0.js"></script>
    <script src="/static/js/underscore-1.3.3-min.js"></script>
    <script src="/static/js/backbone-1.0.0-custom.1-min.js"></script>
    <script src="/static/js/jquery.ba-dotimeout-1.0.0.min.js"></script>
    <script src="/static/js/jquery.masonry-2.1.03.min.js"></script>
    <script src="/static/js/moment-2.5.1.min.js"></script>
    <script src="/static/js/jquery.xcolor-1.8.js"></script>
    <script src="/static/js/jquery.outerHTML-1.0.0.js"></script>
    <script src="/static/js/jquery.fastbutton-1.0.0.js"></script>

    <script>
        window.FLX_RELEASE_NUMBER = "${release}";
    </script>

	<!--  TODO: validate version numbers for these libs -->
	<script src="/static/grapher4/grapher2.nocache.js"></script>

    <!-- switch to main-built for the compiled versino to be laoded -->
    <% if (useMinifiedJs) { %>
	<script data-main="/${release}/js/main-built.js" src="/static/js/require-1.0.3.js"></script>
    <% } else { %>
    <script data-main="/${release}/js/main.js" src="/static/js/require-1.0.3.js"></script>
    <% } %>
</body>
</html>
