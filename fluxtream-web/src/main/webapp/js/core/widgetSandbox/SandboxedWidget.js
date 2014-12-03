define(["core/DashboardWidget"],function(DashboardWidget){

    var SandboxedWidget = function(){

    };

    for (var member in DashboardWidget.prototype) {
        SandboxedWidget.prototype[member] = DashboardWidget.prototype[member];
    }

    SandboxedWidget.prototype.init = function() {
        this.messagerId = ++App.sandboxCounter;
        this.iframe = $("<iframe src= '" + window.location.origin + "/" + window.FLX_RELEASE_NUMBER + "/js/core/widgetSandbox/sandboxHost.html?id=" + this.messagerId + "' class='widgetSandboxIframe' sandbox='allow-scripts' scrolling='no'></iframe>");
        var that = this;
        this.iframe[0].onload = function(){
            /*var script = that.iframe[0].contentDocument.createElement("script");
            script.type = "text/javascript";
            script.innerText += "window.manifest=" + JSON.stringify(that.manifest) + ";";
            script.innerText += "window.messagerId=" + JSON.stringify(that.messagerId) + ";";
            that.iframe[0].contentDocument.head.appendChild(script);
            script = that.iframe[0].contentDocument.createElement("script");
            script.type = "text/javascript";
            script.src = "/" + window.FLX_RELEASE_NUMBER + "/js/core/widgetSandbox/sandboxInitializationScript.js";
            that.iframe[0].contentDocument.head.appendChild(script); */
        };
        this.iframe.addClass("flx-body");
        App.addSandboxMessageListener(this.messageListener);
        $("#" + this.manifest.WidgetName + "-widget .flx-body").replaceWith(this.iframe);

    }

    SandboxedWidget.prototype.isUrlBlocked = function(url) {
        var parser = document.createElement("a");
        parser.href = url;
        if (parser.origin == window.location.origin) {
            return parser.pathname.toLowerCase().startsWith("/api/");
        }
        return false;
    }

    SandboxedWidget.prototype.messageListener = function(event) {
        var message = event.data;
        if (message.messagerId != this.messagerId)
            return;
        if (message.type == "getManifest") {
            this.sendMessage("manifest",this.manifest);
        }
        if (message.widgetName != this.manifest.WidgetName)
            return;
        if (message.WidgetRepositoryURL != this.manifest.WidgetRepositoryURL)
            return;
        if (this.sendInfo != null){
            clearInterval(this.sendInfo);
            this.sendInfo = null;
        }
        if (message.type == "ready") {
            var digestStrippedDown = {};
            this.sendMessage("digest",digestStrippedDown);
        }
        else if (message.type == "ajax") {
            var that = this;
            var ajaxOptions = message.data;
            var requestId = ajaxOptions.id;
            if (this.isUrlBlocked(ajaxOptions.url)) {
                this.sendMessage("ajaxResponse",{
                    id: requestId,
                    success: false
                })
            }
            if (ajaxOptions.dataType == "script")
                ajaxOptions.dataType = "text";
            delete ajaxOptions.id;
            ajaxOptions.success = function(data){
                that.sendMessage("ajaxResponse",{
                    id: requestId,
                    success: true,
                    data: data
                });

            };
            ajaxOptions.error = function(){
                that.sendMessage("ajaxResponse",{
                    id: requestId,
                    success: false
                });

            };
            $.ajax(ajaxOptions);
        }
    }

    SandboxedWidget.prototype.sendMessage = function(type,data) {
        this.iframe[0].contentWindow.postMessage(new Message(type,data),"*");

    }

    function Message(type,data) {
        this.type = type;
        this.data = data;

    }


    return SandboxedWidget;
});