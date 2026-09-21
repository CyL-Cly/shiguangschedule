var isMobile = false;
$(function () {
    if(typeof commonHeaderinit === "function"){
    	commonHeaderinit();
    }
    //如果logo不存在就不显示
    $("#logo").on("error",function(){
    	//如果没有就不显示，防止显示没有图片小图标
    	$(this).hide();
    });

	//校验是否为钉钉小程序，如果是登录form的post改为get
	var interv = setInterval(function(){
		if($("#loginFromId")){
			var ua = navigator.userAgent;
			if(ua.match(/.*iPhone.*DingTalk.*Nebula dd-web.*/)){
				$("#loginFromId").attr("method", "get");
			}
			window.clearInterval(interv);
		}
	},500);

	generateFingerPrint(function(r) {
		console.log('Generated and stored fingerprint');
	});
});

function commonHeaderinit() {
    $.ajax(contextPath + "/tenant/info", {
        dataType: "json",
        success: function (data) {
        	var logoSrc = "";
        	var bgUrl = "";
        	var themeName = data.themeName;
			var themeColorMode = data.themeColorMode;
			if(themeColorMode && themeColorMode == "1"){
				//主题黑白色
				var cssText = "filter:grayscale(100%);-webkit-filter:grayscale(100%);-moz-filter:grayscale(100%);-ms-filter:grayscale(100%);-o-filter:grayscale(100%);"
				utils.addCss($(".root-main"), cssText)
			}
        	if(data.mobileFlay!=undefined && data.mobileFlay){
        		isMobile = true;
        		//移动端：logo设置和背景设置
                if (data.schoolLogoUrlMobile!=undefined && data.schoolLogoUrlMobile!="") {
                	logoSrc = data.schoolLogoUrlMobile;
                }else{
                	logoSrc = contextPath+"/"+themeName+"/static/common/images/mobile_logo.png";
                }
            	if (data.schoolBgUrlMobile!=undefined && data.schoolBgUrlMobile!="") {
            		bgUrl = data.schoolBgUrlMobile;
                }else{
                	bgUrl = contextPath+"/"+themeName+"/static/common/images/mobile_bg.png";
                }
        	}else{
        		//PC端：logo设置和背景设置
                if (data.schoolLogoUrlPc!=undefined && data.schoolLogoUrlPc!="") {
                	logoSrc = data.schoolLogoUrlPc;
                }else{
                	logoSrc = contextPath+"/"+themeName+"/static/common/images/pc_logo.png";
                }
				jsonObject= window.jsonObject==undefined?{}:jsonObject;
				if (jsonObject.defaultImage==1){//自定义
					if (jsonObject.imageType==1){//轮播
						//实现多图轮播
						var swiper = new Swiper('.swiper-container', {
							autoplay: (jsonObject.shufflingTime)*1000,	//轮播时间
							autoplayDisableOnInteraction: false, // 手动切换之后继续自动轮播
							speed: jsonObject.shufflingStyle == 1 ? 5 : 1000, // 切换速度(1切换 2平移)
							loop: true,
							pagination: '.swiper-pagination',
							paginationClickable: true
						})
					}
				}else{//默认
					if (data.schoolBgUrlPc!=undefined && data.schoolBgUrlPc!="") {
						bgUrl = data.schoolBgUrlPc;//取数据库
					}else{//取初始图片
						bgUrl = contextPath+"/"+themeName+"/static/common/images/pc_bg.png";
					}
				}
        	}
        	$("#logo").attr("src", logoSrc);
			$("#bg").css("background-image", "url(" + bgUrl + ")")
			$("#bg").css("background-size","100% 100%")
			$("#bg").css("background-attachment","fixed");
        }
    });
}

//设置浏览器cookie
function setBFP(_3b){
	$.ajax(contextPath + "/bfp/info", {
		data: { bfp: _3b },
		cache: false,
		dataType: "json",
		success: function (data) {

		}
	});

}

function getCookie(cookie_name) {
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
}

// 通过文件名获取当前脚本URL
function getCurrentScriptUrl() {
	var scripts = document.getElementsByTagName('script');
	var currentScript = null;
	for (var i = 0; i < scripts.length; i++) {
		if (scripts[i].src.indexOf('common-header.js') !== -1) {
			currentScript = scripts[i];
			break;
		}
	}
	return currentScript ? currentScript.src : '';
}

// 获取基础路径
function getBasePath() {
	var url = getCurrentScriptUrl();
	if (!url) {
		console.error('Failed to get current script URL');
		return '';
	}
	return url.substring(0, url.lastIndexOf('/') + 1);
}

// 1. 加载本地JS文件
function loadScript(scriptName) {
	// 获取当前可用的Promise实现
	var PromiseImpl = window.Promise || (function() {
		var PromisePolyfill = function(executor) {
			var self = this;
			self.status = 'pending';
			self.value = undefined;
			self.reason = undefined;
			self.onFulfilledCallbacks = [];
			self.onRejectedCallbacks = [];

			function resolve(value) {
				if (self.status === 'pending') {
					self.status = 'fulfilled';
					self.value = value;
					self.onFulfilledCallbacks.forEach(function(callback) {
						callback(value);
					});
				}
			}

			function reject(reason) {
				if (self.status === 'pending') {
					self.status = 'rejected';
					self.reason = reason;
					self.onRejectedCallbacks.forEach(function(callback) {
						callback(reason);
					});
				}
			}

			try {
				executor(resolve, reject);
			} catch (e) {
				reject(e);
			}
		};

		PromisePolyfill.prototype.then = function(onFulfilled, onRejected) {
			var self = this;
			var promise2 = new PromisePolyfill(function(resolve, reject) {
				if (self.status === 'fulfilled') {
					setTimeout(function() {
						try {
							var x = onFulfilled(self.value);
							resolve(x);
						} catch (e) {
							reject(e);
						}
					}, 0);
				}
				if (self.status === 'rejected') {
					setTimeout(function() {
						try {
							var x = onRejected(self.reason);
							resolve(x);
						} catch (e) {
							reject(e);
						}
					}, 0);
				}
				if (self.status === 'pending') {
					self.onFulfilledCallbacks.push(function(value) {
						setTimeout(function() {
							try {
								var x = onFulfilled(value);
								resolve(x);
							} catch (e) {
								reject(e);
							}
						}, 0);
					});
					self.onRejectedCallbacks.push(function(reason) {
						setTimeout(function() {
							try {
								var x = onRejected(reason);
								resolve(x);
							} catch (e) {
								reject(e);
							}
						}, 0);
					});
				}
			});
			return promise2;
		};

		PromisePolyfill.prototype.catch = function(onRejected) {
			return this.then(null, onRejected);
		};

		return PromisePolyfill;
	})();

	return new PromiseImpl(function(resolve, reject) {
		var basePath = getBasePath();
		if (!basePath) {
			reject(new Error('Invalid base path'));
			return;
		}

		var fullPath = basePath + scriptName;
		console.log('Loading script from: ' + fullPath); // 调试用

		var script = document.createElement('script');
		script.src = fullPath;

		script.onload = function() {
			console.log('Successfully loaded: ' + fullPath);
			resolve();
		};

		script.onerror = function(error) {
			console.error('Failed to load: ' + fullPath, error);
			reject(error);
		};

		document.head.appendChild(script);
	});
}

// 2. 获取浏览器信息
function getNavigatorInfo() {
	return {
		platform: navigator.platform,
		language: navigator.language,
		hardwareConcurrency: navigator.hardwareConcurrency || 0,
		maxTouchPoints: navigator.maxTouchPoints || 0,
		deviceMemory: navigator.deviceMemory || 0
	};
}

// 3. 生成Canvas指纹
function createDataUrl() {
	var canvas = document.createElement("canvas");
	canvas.setAttribute("width", 220);
	canvas.setAttribute("height", 30);
	var ctx = canvas.getContext("2d");

	var txt = "WiseduCiap,com <canvas> 1.0";
	ctx.textBaseline = "top";
	ctx.font = "14px 'Arial'";
	ctx.textBaseline = "alphabetic";
	ctx.fillStyle = "#f60";
	ctx.fillRect(112, 1, 62, 20);
	ctx.fillStyle = "#069";
	ctx.fillText(txt, 2, 15);
	ctx.fillStyle = "rgba(102, 204, 0, 0.7)";
	ctx.fillText(txt, 4, 17);

	var dataUrl = canvas.toDataURL();
	var rawData = atob(dataUrl.split(",")[1]);

	return {
		dataUrl : dataUrl,
		rawData : rawData
	};
}

// 4. 生成Hash
function createHash(rawData) {
	if (!rawData) {
		return "N/A";
	}
	var dataArr;
	try {
		dataArr = new Uint8Array(rawData.length);
		for (var i = 0; i < rawData.length; i++) {
			dataArr[i] = rawData.charCodeAt(i);
		}
	} catch(e) {
		// 如果 Uint8Array 不可用，使用普通数组
		dataArr = [];
		for (var i = 0; i < rawData.length; i++) {
			dataArr.push(rawData.charCodeAt(i));
		}
	}
	return md5(dataArr).toUpperCase();
}

// 5. 生成混合Hash
function createMixHash(browserInfo, navigatorInfo, canvasHash) {
	var items = [];

	// 添加浏览器信息
	items.push((browserInfo.browser || {}).name || "-");
	items.push((browserInfo.engine || {}).name || "-");
	items.push((browserInfo.os || {}).name || "-");
	items.push((browserInfo.cpu || {}).architecture || "-");
	items.push((browserInfo.device || {}).type || "-");
	items.push((browserInfo.device || {}).model || "-");
	items.push((browserInfo.device || {}).vendor || "-");

	// 添加navigator信息
	items.push(navigatorInfo.platform);
	items.push(navigatorInfo.language);
	items.push(navigatorInfo.hardwareConcurrency);
	items.push(navigatorInfo.maxTouchPoints);
	items.push(navigatorInfo.deviceMemory);

	// 添加canvas hash
	items.push(canvasHash);

	return md5(items.join("|")).toUpperCase();
}

// 6. 生成完整指纹
function generateFingerPrint(callback) {
	try {
		// 获取当前可用的Promise实现
		var PromiseImpl = window.Promise || (function() {
			var PromisePolyfill = function(executor) {
				var self = this;
				self.status = 'pending';
				self.value = undefined;
				self.reason = undefined;
				self.onFulfilledCallbacks = [];
				self.onRejectedCallbacks = [];

				function resolve(value) {
					if (self.status === 'pending') {
						self.status = 'fulfilled';
						self.value = value;
						self.onFulfilledCallbacks.forEach(function(callback) {
							callback(value);
						});
					}
				}

				function reject(reason) {
					if (self.status === 'pending') {
						self.status = 'rejected';
						self.reason = reason;
						self.onRejectedCallbacks.forEach(function(callback) {
							callback(reason);
						});
					}
				}

				try {
					executor(resolve, reject);
				} catch (e) {
					reject(e);
				}
			};

			PromisePolyfill.prototype.then = function(onFulfilled, onRejected) {
				var self = this;
				var promise2 = new PromisePolyfill(function(resolve, reject) {
					if (self.status === 'fulfilled') {
						setTimeout(function() {
							try {
								var x = onFulfilled(self.value);
								resolve(x);
							} catch (e) {
								reject(e);
							}
						}, 0);
					}
					if (self.status === 'rejected') {
						setTimeout(function() {
							try {
								var x = onRejected(self.reason);
								resolve(x);
							} catch (e) {
								reject(e);
							}
						}, 0);
					}
					if (self.status === 'pending') {
						self.onFulfilledCallbacks.push(function(value) {
							setTimeout(function() {
								try {
									var x = onFulfilled(value);
									resolve(x);
								} catch (e) {
									reject(e);
								}
							}, 0);
						});
						self.onRejectedCallbacks.push(function(reason) {
							setTimeout(function() {
								try {
									var x = onRejected(reason);
									resolve(x);
								} catch (e) {
									reject(e);
								}
							}, 0);
						});
					}
				});
				return promise2;
			};

			PromisePolyfill.prototype.catch = function(onRejected) {
				return this.then(null, onRejected);
			};

			PromisePolyfill.all = function(promises) {
				return new PromisePolyfill(function(resolve, reject) {
					var results = [];
					var remaining = promises.length;

					if (remaining === 0) {
						resolve(results);
						return;
					}

					function handlePromise(index, value) {
						results[index] = value;
						remaining--;
						if (remaining === 0) {
							resolve(results);
						}
					}

					for (var i = 0; i < promises.length; i++) {
						(function(i) {
							promises[i].then(function(value) {
								handlePromise(i, value);
							}, reject);
						})(i);
					}
				});
			};

			return PromisePolyfill;
		})();

		// 加载依赖
		PromiseImpl.all([
			loadScript('ua-parser.min.js'),
			loadScript('md5.min.js')
		]).then(function() {
            //从 cookie 获取
            var  fingerprint = getCookie("MULTIFACTOR_BROWSER_FINGERPRINT");
            if(fingerprint == "" || fingerprint == null) {
				//从localStorage 获取
				fingerprint = window.localStorage.getItem("browser_fingerprint");
				if(fingerprint == "" || fingerprint == null){
                    // 获取浏览器信息
                    var navigatorInfo = getNavigatorInfo();
                    var browserInfo = new UAParser().getResult();
                    // 生成canvas指纹
                    var canvasData = createDataUrl();
                    // 生成hash
                    var canvasHash = createHash(canvasData.rawData);
                    // 生成混合hash
                    fingerprint = createMixHash(browserInfo, navigatorInfo, canvasHash);
                    window.localStorage.setItem("browser_fingerprint", fingerprint);
                }
                // 存储到cookie，设置30天过期
                setBFP(fingerprint);
			}
			callback(fingerprint);
		}).catch(function(error) {
			console.error('Failed to generate fingerprint:', error);
			callback(null);
		});
	} catch (error) {
		console.error('Failed to generate fingerprint:', error);
		callback(null);
	}
}