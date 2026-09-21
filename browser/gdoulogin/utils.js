window.onload = function(){
	//用于处理ajax请求时如果后台登录失效被拦截了，然后302无法跳转登录问题
	$.ajaxSetup({
	    contentType: "application/x-www-form-urlencoded;charset=utf-8",
	    complete: function(XMLHttpRequest, textStatus) {
	    	if(XMLHttpRequest && XMLHttpRequest.responseJSON){
	    		var response = XMLHttpRequest.responseJSON;
	    		if(response.errCode && response.errCode == 206302){
	    			window.location.reload();
	    		}
	    	}
	    }
	});
};

$(function(){
	// 7.2.1.SP4从window.onload移入 需要在该文件加载前加载toastr.js
	// 初始化 totast
	// 使用方式：toastr.success('Hello world!', 'Toastr fun!');
	//success 、 error、info、warning 第二个参数为标题可以省略
	if(typeof(toastr) != 'undefined'){
		toastr.options = {
			closeButton: false,
			debug: false,
			progressBar: false,
			positionClass: 'toast-top-center',
			onclick: null,
			showDuration: '300',
			hideDuration: '1000',
			timeOut: '4000',
			extendedTimeOut: '1000',
			showEasing: 'swing',
			hideEasing: 'linear',
			showMethod: 'fadeIn',
			hideMethod: 'fadeOut'
		};
	}
	//因内容过长会截取，所以需设置title用于显示完成内容(只能显示一行)
	$(".lang_text_ellipsis").each(function(index){
		$(this).attr("title", $(this).text());
		var ellipsis_css = "text-overflow:ellipsis;" +
			"overflow:hidden;" +
			"white-space:nowrap;" +
			"display:-moz-inline-box;" +
			"word-wrap:break-word;"
		var display = $(this).css("display");
		if(display && display == 'none'){
			ellipsis_css = ellipsis_css +"display:none;"
		}else{
			ellipsis_css = ellipsis_css +"display:inline-block;"
		}
		utils.addCss(this, ellipsis_css);
	});

	//因内容过长会截取，所以需设置title用于显示完成内容(通过后面数字显示多少行，例显示5行num-text-ellipsis-5)
	$("*[class*=num-text-ellipsis]").each(function(index){
		var webkitLine = 1;
		var className = $(this)[0].className;
		$.each(className.split(" "), function(index, obj){
			if(obj.indexOf("num-text-ellipsis-") >= 0){
				webkitLine = obj.replace("num-text-ellipsis-", "");
			}
		});
		$(this).attr("title", $(this).text());
		var ellipsis_css = "text-overflow:ellipsis;" +
			"overflow:hidden;" +
			"white-space:inherit;" +
			"word-wrap:break-word;" +
			"display:-webkit-box;" +
			"-webkit-line-clamp:"+webkitLine+";" +
			"-webkit-box-orient:vertical;";
		var display = $(this).css("display");
		if(display && display == "none"){
			ellipsis_css = ellipsis_css + "display:none";
		}else{
			ellipsis_css = ellipsis_css + "display:-webkit-box";
		}
		utils.addCss(this, ellipsis_css);
	});

	//input框只能输入数字，如手机号
	$(".only-input-number").each(function(){
		$(this).keyup(function(){
			$(this).val($(this).val().replace(/[^\d]/g,''));
		})
	});
});

//清除帐号激活和找回密码存入localStorage的临时数据service
localStorage.removeItem("accountActivation");
localStorage.removeItem("retrievePassword");

//禁止页面输入框自动填入
function reloadInput(){
	$(".no-auto-input").each(function(index){
		// $(this).val('');
		//因IE对autocomplete兼容性不支持，会出现输入框点击2次才能输入，所以如果IE就不加该属性
		if (!(window.ActiveXObject || "ActiveXObject" in window)){
			$(this).attr('readonly','readonly');
			$(this).attr('autocomplete','off');
		}
	});
	setTimeout(function(){
		$(".no-auto-input").each(function(index){
			// $(this).val('');
			$(this).removeAttr("readonly");
		});
	},1500);
}

var utils = {
	disabledBtnCss: {},
	//全局提示信息显示时长
	defaultShowMsgTime: 4000,
	//全局提示信息显示长度(防止字符过长显示不美观)
	defaultShowMsgLength: 100,
	// 检查对象元素是否存在
	isNull: function(data) {
		if (data == null || data == undefined || $(data).length <= 0) {
			return true;
		}
		return false;
	},

	// 检查字符串是否为空（包括空格字符串）
	isEmptyStr: function(data) {
        if (data == null || data == undefined || data == ""  || $.trim(data) == "") {
            return true;
		}
		return false;
	},
	isNotEmptyStr: function(data) {
		return !this.isEmptyStr(data);
	},
	subStr: function(data, num) {
		if(!num || num==0){
			num = this.defaultShowMsgLength;
		}
		var locale = this.getCookie("org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE");
		if(this.isNotEmptyStr(locale) && locale.indexOf("en") != -1){
			num = 2 * num;//英文字符就多截取
		}
		if(this.isEmptyStr(data) || num<=0 || data.length<=num){
			return data;
		}
		return data.substring(0,num)+"...";
	},
	// null和defined转为"",defaultVal为默认值
	toStr: function(data, defaultVal) {
		if (this.isEmptyStr(defaultVal)) {
			return this.isEmptyStr(data) ? "" : data;
		} else {
			return this.isEmptyStr(data) ? defaultVal : data;
		}
	},
	
	//给A标签href属性url拼接参数（注意paramName参数符号 & or ?）
	setUrlParam: function(id, paramName, paramValue){
		var eltObj = document.getElementById(id);
		if(!eltObj || this.isEmptyStr(paramName) || this.isEmptyStr(paramValue)){
			return;
		}
		if(eltObj.nodeName == "A" || eltObj.nodeName == "a"){
			//a标签
			eltObj.href = eltObj.href+paramName+"="+paramValue;
		}else if(eltObj.nodeName == "FORM" || eltObj.nodeName == "form"){
			//form标签
			eltObj.action = eltObj.action+paramName+"="+paramValue;
		}
	},
	
	//统一input输入校验提示
	requireInput: function(that, minLength, maxLength, msgThat, errorMsg, requireObj,needFocus){
		var isRequire = false;
		if(this.isNull(that)){
			this.alertBox("代码错误！", 3000);
			return true;
		}
		
		if(!requireObj){
			requireObj = that;
		}
		//页面输入框校验
		var thatVal = $(that).val();
		if(thatVal != undefined){
			thatVal = thatVal.replace(/(^\s*)|(\s*$)/g, "");//正则去空格
			$(that).val(thatVal);
			if(thatVal.length <= minLength || thatVal.length > maxLength){
				isRequire = true;
				this.addClass(requireObj, "required");
				this.showMsg(msgThat,errorMsg);
				if(needFocus==undefined||needFocus=="true"){
					$(that).focus();
				}
				$(msgThat).show();
			}else{
				isRequire = false;
				this.removeClass(requireObj, "required");
				this.showMsg(msgThat,"");
			}
		}
		return isRequire;
	},
	//清除requrie样式
	cleanRequire: function(requireObj, showErrorObj){
		$(requireObj).find(".required").each(function(){
			utils.removeClass($(this), "required");
		});
		$(showErrorObj).empty();
	},
	
	//统一input输入校验插件弹框提示
	toastRequireInput: function(that, minLength, maxLength, errorMsg){
		var isRequire = false;
		if(this.isNull(that)){
			this.alertBox("代码错误！", 3000);
			return true;
		}
		//页面输入框校验
		var thatVal = $(that).val();
		if(thatVal != undefined){
			thatVal = thatVal.replace(/(^\s*)|(\s*$)/g, "");//正则去空格
			$(that).val(thatVal);
			if(thatVal.length <= minLength || thatVal.length > maxLength){
				isRequire = true;
				this.addClass(that, "required");
				this.alertBox(errorMsg);
			}else{
				isRequire = false;
				this.removeClass(that, "required");
			}
		}
		return isRequire;
	},
	
	//弹窗显示提示信息
	alertBox: function(msg, mTime, mLength) {
		if(!mTime || mTime<=0){
			//默认显示时长
			mTime = this.defaultShowMsgTime;
		}
		if(msg){
			msg = msg.replace(/(^\s*)|(\s*$)/g, "");//正则去空格
		}else{
			return false;
		}
		//防止提示信息过长
		var mssg = this.subStr(msg, mLength);
	    // 防止连续点击造成的多个提示框
	    if (document.querySelector("#alert-box")) {
	        document.body.removeChild(document.querySelector("#alert-box"));
	    }
		var alertBox = document.createElement("div");
		alertBox.id = "alert-box";
		alertBox.title = msg;
	    
	    var clientWidth = document.body.clientWidth;
	    if(clientWidth > 1000){
	    	//PC端
	    	clientWidth = clientWidth * 0.3;
	    }else{
	    	//移动端
	    	clientWidth = clientWidth * 0.6;
	    }
		alertBox.style.cssText = "" +
			"min-width:120px;" +
			"max-width:"+clientWidth+"px;" +
			"min-height:120px;" +
			"background:#121212;" +
			"color:#FFFFFF;" +
			"font-size:16px;" +
			"text-align:center;" +
			"border-radius:5px;" +
			"opacity: 0.8;" +
			"z-index:999;" +
			"padding:21px 14px;" +
			"-webkit-box-sizing: border-box;" +
			"-moz-box-sizing: border-box;" +
			"box-sizing: border-box;" +
			"display:inline-block;" +
			"word-wrap: break-word;" +
			"overflow: hidden;" +
			"text-overflow:ellipsis;" +
			"position:absolute;" +
			"top:35%;" +
			"left:50%;" +
			"transform: translate(-50%, -50%);";

	    //设置弹框提示图标
		var img = document.createElement("img");
		img.src = "/authserver/default/static/common/images/error-tip.png";
		img.style.cssText = "width: 48px;height: 48px;margin: 0px 0px 8px 0px;";
		alertBox.appendChild(img)
		//设置提示语
		var msgDiv = document.createElement("div");
		msgDiv.style.cssText = "width: 100%;" +
			"text-overflow: ellipsis;" +
			"overflow: hidden;" +
			"white-space: inherit;" +
			"overflow-wrap: break-word;" +
			"-webkit-line-clamp: 3;" +
			"-webkit-box-orient: vertical;" +
			"display: -webkit-box;";
		msgDiv.innerHTML = mssg;
		alertBox.appendChild(msgDiv)
	    document.body.appendChild(alertBox);
	    var bhAlertBox = document.querySelector("#alert-box");
	    setTimeout(function () {
	        // 防止前面提示框丢弃之后导致js报错
	        if (bhAlertBox.parentNode != null) {
	            document.body.removeChild(bhAlertBox);
	        }
	    }, mTime)
	},
	
	//指定页面位置展示提示信息
	showMsg: function(msgObj, msg, msTime, mLength){
		if(this.isNull(msgObj)){
			return;
		}
		if(this.isEmptyStr(msg)){
			return;
		}
		msg = msg.replace(/(^\s*)|(\s*$)/g, "");//正则去空格
		$(msgObj)[0].title=msg;
		$(msgObj)[0].innerHTML=this.subStr(msg, mLength);
		//提示信息多久时间后消失
		if(msTime != undefined){
			if(msTime <= 0){
				msTime = this.defaultShowMsgTime;
			}
			$(msgObj).show().delay(msTime).hide("slow")
		}
		return;
	},

	//指定页面位置展示提示信息
	showDelayMsg: function(msgObj, msg, msTime, mLength){
		if(this.isNull(msgObj)){
			return;
		}
		if(this.isEmptyStr(msg)){
			return;
		}
		msg = msg.replace(/(^\s*)|(\s*$)/g, "");//正则去空格
		$(msgObj)[0].innerHTML=this.subStr(msg, mLength);
		//提示信息多久时间后消失
		if(msTime != undefined){
			if(msTime <= 0){
				msTime = this.defaultShowMsgTime;
			}
			$(msgObj).show().delay(msTime).hide("slow")
		}
		return;
	},

	//JS原生方式-添加元素指定样式
	addCss: function(that, cssJson){
		if(this.isNull(that)){
			return;
		}
		//$(that).css(cssJson);
		//var obj = document.getElementById("btnB");
		$(that)[0].style.cssText += ";"+cssJson;
	},
	//JS原生方式-添加元素指定样式
	addClass: function(that, cls){
		if(this.isNull(that)){
			return;
		}
		//$(that).addClass("required");//IE不支持jquery.addClass()方法
		$(that)[0].className += " "+cls;
	},
	
	//JS原生方式-删除元素指定样式
	removeClass: function removeClass(that, cls){
		if(this.isNull(that)){
			return;
		}
		var obj = $(that)[0];
		removed = obj.className.replace(new RegExp(cls,"gm"), '');
		removed = removed.replace(/(^\s+)|(\s+$)/g, '');//去掉首尾空格
	    obj.className = removed;//替换原来的 class.
	},
	
	//设置浏览器cookie
	setCookie: function(_3a,_3b){
	    var secure = window.location.protocol;
	    if (secure == 'https:') {
	        document.cookie=_3a+"="+escape(_3b)+";path=/"+";secure;SameSite=None;expires="+(new Date(2099,12,31)).toGMTString();
	    }else {
	        document.cookie=_3a+"="+escape(_3b)+";path=/"+";expires="+(new Date(2099,12,31)).toGMTString();
	    }
	},

	//获取浏览器cookie
	getCookie: function(cookie_name) {
	    var allcookies = document.cookie;
	    var cookie_pos = allcookies.indexOf(cookie_name);
	    if (cookie_pos != -1) {
	        cookie_pos += cookie_name.length + 1;
	        var cookie_end = allcookies.indexOf(";", cookie_pos);
	        if (cookie_end == -1) {
	            cookie_end = allcookies.length;
	        }
	        var value = unescape(allcookies.substring(cookie_pos, cookie_end));
	    }
	    return value;
	},

	//统一按钮点击置灰
	disabledBtn: function(that, flag){
		if(flag){
			//按钮置灰不可点击
			this.disabledBtnCss[$(that).attr("class")] = $(that).css("background-color");
			$(that).attr("disabled",true);
			$(that).css("background-color","#cccfd2");
			$(that).css("cursor","not-allowed");
			$(that).css("pointer-events","none");
		}else{
			$(that).attr("disabled",false);
			$(that).css("background-color",this.disabledBtnCss[$(that).attr("class")]);
			$(that).css("pointer-events","");
			$(that).css("cursor","pointer");
		}
	}
}
