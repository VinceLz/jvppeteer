package com.ruiyun.jvppeteer.core.page;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruiyun.jvppeteer.Constant;
import com.ruiyun.jvppeteer.core.browser.Browser;
import com.ruiyun.jvppeteer.core.browser.BrowserContext;
import com.ruiyun.jvppeteer.events.DefaultBrowserListener;
import com.ruiyun.jvppeteer.events.EventEmitter;
import com.ruiyun.jvppeteer.events.EventHandler;
import com.ruiyun.jvppeteer.events.Events;
import com.ruiyun.jvppeteer.exception.NavigateException;
import com.ruiyun.jvppeteer.exception.PageCrashException;
import com.ruiyun.jvppeteer.exception.TerminateException;
import com.ruiyun.jvppeteer.options.ClickOptions;
import com.ruiyun.jvppeteer.options.Clip;
import com.ruiyun.jvppeteer.options.ClipOverwrite;
import com.ruiyun.jvppeteer.options.Device;
import com.ruiyun.jvppeteer.options.PDFOptions;
import com.ruiyun.jvppeteer.options.PageNavigateOptions;
import com.ruiyun.jvppeteer.options.ScreenshotOptions;
import com.ruiyun.jvppeteer.options.ScriptTagOptions;
import com.ruiyun.jvppeteer.options.StyleTagOptions;
import com.ruiyun.jvppeteer.options.Viewport;
import com.ruiyun.jvppeteer.options.WaitForSelectorOptions;
import com.ruiyun.jvppeteer.protocol.DOM.Margin;
import com.ruiyun.jvppeteer.protocol.PageEvaluateType;
import com.ruiyun.jvppeteer.protocol.console.Location;
import com.ruiyun.jvppeteer.protocol.emulation.MediaFeature;
import com.ruiyun.jvppeteer.protocol.emulation.ScreenOrientation;
import com.ruiyun.jvppeteer.protocol.log.DialogType;
import com.ruiyun.jvppeteer.protocol.log.EntryAddedPayload;
import com.ruiyun.jvppeteer.protocol.network.Cookie;
import com.ruiyun.jvppeteer.protocol.network.CookieParam;
import com.ruiyun.jvppeteer.protocol.network.DeleteCookiesParameters;
import com.ruiyun.jvppeteer.protocol.page.FileChooserOpenedPayload;
import com.ruiyun.jvppeteer.protocol.page.GetNavigationHistoryReturnValue;
import com.ruiyun.jvppeteer.protocol.page.JavascriptDialogOpeningPayload;
import com.ruiyun.jvppeteer.protocol.page.NavigationEntry;
import com.ruiyun.jvppeteer.protocol.performance.Metric;
import com.ruiyun.jvppeteer.protocol.performance.Metrics;
import com.ruiyun.jvppeteer.protocol.performance.MetricsPayload;
import com.ruiyun.jvppeteer.protocol.performance.PageMetrics;
import com.ruiyun.jvppeteer.protocol.runtime.BindingCalledPayload;
import com.ruiyun.jvppeteer.protocol.runtime.ConsoleAPICalledPayload;
import com.ruiyun.jvppeteer.protocol.runtime.ExceptionDetails;
import com.ruiyun.jvppeteer.protocol.runtime.RemoteObject;
import com.ruiyun.jvppeteer.protocol.runtime.StackTrace;
import com.ruiyun.jvppeteer.protocol.webAuthn.Credentials;
import com.ruiyun.jvppeteer.transport.CDPSession;
import com.ruiyun.jvppeteer.transport.Connection;
import com.ruiyun.jvppeteer.util.Helper;
import com.ruiyun.jvppeteer.util.StringUtil;
import com.ruiyun.jvppeteer.util.ValidateUtil;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
import sun.misc.BASE64Decoder;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.ruiyun.jvppeteer.Constant.OBJECTMAPPER;
import static com.ruiyun.jvppeteer.Constant.RECV_MESSAGE_STREAM_PROPERTY;
import static com.ruiyun.jvppeteer.Constant.supportedMetrics;

public class Page extends EventEmitter {

    private Set<BiConsumer<Object, FileChooser>> fileChooserInterceptors;

    private boolean closed = true;

    @JsonIgnore
    private CDPSession client;

    private Target target;

    @JsonIgnore
    private Keyboard keyboard;

    @JsonIgnore
    private Mouse mouse;

    private TimeoutSettings timeoutSettings;

    @JsonIgnore
    private Touchscreen touchscreen;

    @JsonIgnore
    private Accessibility accessibility;

    @JsonIgnore
    private FrameManager frameManager;

    @JsonIgnore
    private EmulationManager emulationManager;

    @JsonIgnore
    private Tracing tracing;

    private Map<String, Function<List<JsonNode>, Object>> pageBindings;

    @JsonIgnore
    private Coverage coverage;

    private boolean javascriptEnabled;

    private Viewport viewport;

    private TaskQueue screenshotTaskQueue;

    private Map<String, Worker> workers;

    public static final String ABOUT_BLANK = "about:blank";

    public static final Map<String, Double> unitToPixels = new HashMap() {
        private static final long serialVersionUID = -4861220887908575532L;

        {
            put("px", 1.00);
            put("in", 96);
            put("cm", 37.8);
            put("mm", 3.78);
        }
    };

    public Page(CDPSession client, Target target, boolean ignoreHTTPSErrors, TaskQueue screenshotTaskQueue) {
        super();
        this.closed = false;
        this.client = client;
        this.client.getConnection();
        this.target = target;
        this.keyboard = new Keyboard(client);
        this.mouse = new Mouse(client, keyboard);
        this.timeoutSettings = new TimeoutSettings();
        this.touchscreen = new Touchscreen(client, keyboard);
        this.accessibility = new Accessibility(client);
        this.frameManager = new FrameManager(client, this, ignoreHTTPSErrors, timeoutSettings);
        this.emulationManager = new EmulationManager(client);
        this.tracing = new Tracing(client);
        this.pageBindings = new HashMap<>();
        this.coverage = new Coverage(client);
        this.javascriptEnabled = true;
        this.viewport = null;
        this.screenshotTaskQueue = screenshotTaskQueue;
        this.workers = new HashMap<>();
        DefaultBrowserListener<Target> attachedListener = new DefaultBrowserListener<Target>() {
            @Override
            public void onBrowserEvent(Target event) {
                Page page = (Page) this.getTarget();
                if (!"worker".equals(event.getTargetInfo().getType())) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("sessionId", event.getSessionId());
                        /*
                          If we don't detach from service workers, they will never die
                         */
                    client.send("Target.detachFromTarget", params, false);
                    return;
                }
                CDPSession session = Connection.fromSession(page.getClient()).session(event.getSessionId());
                Worker worker = new Worker(session, event.getTargetInfo().getUrl(), page::addConsoleMessage, page::handleException);
                page.getWorkers().putIfAbsent(event.getSessionId(), worker);
                page.emit(Events.PAGE_WORKERCREATED.getName(), worker);
            }
        };
        attachedListener.setMothod("Target.attachedToTarget");
        attachedListener.setTarget(this);
        attachedListener.setResolveType(Target.class);
        this.client.on(attachedListener.getMothod(), attachedListener);

        DefaultBrowserListener<Target> detachedListener = new DefaultBrowserListener<Target>() {
            @Override
            public void onBrowserEvent(Target event) {
                Page page = (Page) this.getTarget();
                Worker worker = page.getWorkers().get(event.getSessionId());
                if (worker == null) {
                    return;
                }
                page.emit(Events.PAGE_WORKERDESTROYED.getName(), worker);
                page.getWorkers().remove(event.getSessionId());
            }
        };
        detachedListener.setMothod("Target.detachedFromTarget");
        detachedListener.setTarget(this);
        detachedListener.setResolveType(Target.class);
        this.client.on(detachedListener.getMothod(), detachedListener);

        DefaultBrowserListener<Object> frameAttachedListener = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_FRAMEATTACHED.getName(), event);
            }
        };
        frameAttachedListener.setMothod(Events.FRAME_MANAGER_FRAME_ATTACHED.getName());
        frameAttachedListener.setTarget(this);
        this.frameManager.on(frameAttachedListener.getMothod(), frameAttachedListener);

        DefaultBrowserListener<Object> frameDetachedListener = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_FRAMEDETACHED.getName(), event);
            }
        };
        frameDetachedListener.setMothod(Events.FRAME_MANAGER_FRAME_DETACHED.getName());
        frameDetachedListener.setTarget(this);
        this.frameManager.on(frameDetachedListener.getMothod(), frameDetachedListener);

        DefaultBrowserListener<Object> frameNavigatedListener = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_FRAMENAVIGATED.getName(), event);
            }
        };
        frameNavigatedListener.setMothod(Events.FRAME_MANAGER_FRAME_NAVIGATED.getName());
        frameNavigatedListener.setTarget(this);
        this.frameManager.on(frameNavigatedListener.getMothod(), frameNavigatedListener);

        NetworkManager networkManager = this.frameManager.getNetworkManager();

        DefaultBrowserListener<Request> requestLis = new DefaultBrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_REQUEST.getName(), event);
            }
        };
        requestLis.setMothod(Events.NETWORK_MANAGER_REQUEST.getName());
        requestLis.setTarget(this);
        networkManager.on(requestLis.getMothod(), requestLis);

        DefaultBrowserListener<Response> responseLis = new DefaultBrowserListener<Response>() {
            @Override
            public void onBrowserEvent(Response event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_RESPONSE.getName(), event);
            }
        };
        responseLis.setMothod(Events.NETWORK_MANAGER_RESPONSE.getName());
        responseLis.setTarget(this);
        networkManager.on(responseLis.getMothod(), responseLis);

        DefaultBrowserListener<Request> requestFailedLis = new DefaultBrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_REQUESTFAILED.getName(), event);
            }
        };
        requestFailedLis.setMothod(Events.NETWORK_MANAGER_REQUEST_FAILED.getName());
        requestFailedLis.setTarget(this);
        networkManager.on(requestFailedLis.getMothod(), requestFailedLis);

        DefaultBrowserListener<Request> requestFinishedLis = new DefaultBrowserListener<Request>() {
            @Override
            public void onBrowserEvent(Request event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_REQUESTFINISHED.getName(), event);
            }
        };
        requestFinishedLis.setMothod(Events.NETWORK_MANAGER_REQUEST_FINISHED.getName());
        requestFinishedLis.setTarget(this);
        networkManager.on(requestFinishedLis.getMothod(), requestFinishedLis);

        //TODO ${fileChooserInterceptors}
        this.fileChooserInterceptors = new HashSet<>();

        DefaultBrowserListener<Object> domContentEventFiredLis = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_DOMContentLoaded.getName(), event);
            }
        };
        domContentEventFiredLis.setMothod("Page.domContentEventFired");
        domContentEventFiredLis.setTarget(this);
        this.client.on(domContentEventFiredLis.getMothod(), domContentEventFiredLis);

        DefaultBrowserListener<Object> loadEventFiredLis = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.emit(Events.PAGE_LOAD.getName(), event);
            }
        };
        loadEventFiredLis.setMothod("Page.loadEventFired");
        loadEventFiredLis.setTarget(this);
        this.client.on(loadEventFiredLis.getMothod(), loadEventFiredLis);

        DefaultBrowserListener<ConsoleAPICalledPayload> consoleAPICalledLis = new DefaultBrowserListener<ConsoleAPICalledPayload>() {
            @Override
            public void onBrowserEvent(ConsoleAPICalledPayload event) {
                Page page = (Page) this.getTarget();
                page.onConsoleAPI(event);
            }
        };
        consoleAPICalledLis.setMothod("Runtime.consoleAPICalled");
        consoleAPICalledLis.setTarget(this);
        this.client.on(consoleAPICalledLis.getMothod(), consoleAPICalledLis);

        DefaultBrowserListener<BindingCalledPayload> bindingCalledLis = new DefaultBrowserListener<BindingCalledPayload>() {
            @Override
            public void onBrowserEvent(BindingCalledPayload event) {
                Page page = (Page) this.getTarget();
                page.onBindingCalled(event);
            }
        };
        bindingCalledLis.setMothod("Runtime.bindingCalled");
        bindingCalledLis.setTarget(this);
        this.client.on(bindingCalledLis.getMothod(), bindingCalledLis);

        DefaultBrowserListener<JavascriptDialogOpeningPayload> javascriptDialogOpeningLis = new DefaultBrowserListener<JavascriptDialogOpeningPayload>() {
            @Override
            public void onBrowserEvent(JavascriptDialogOpeningPayload event) {
                Page page = (Page) this.getTarget();
                page.onDialog(event);
            }
        };
        javascriptDialogOpeningLis.setMothod("Page.javascriptDialogOpening");
        javascriptDialogOpeningLis.setTarget(this);
        this.client.on(javascriptDialogOpeningLis.getMothod(), javascriptDialogOpeningLis);

        DefaultBrowserListener<JsonNode> exceptionThrownLis = new DefaultBrowserListener<JsonNode>() {
            @Override
            public void onBrowserEvent(JsonNode event) {
                Page page = (Page) this.getTarget();
                JsonNode exceptionDetails = event.get("exceptionDetails");
                try {
                    if (exceptionDetails == null) {
                        return;
                    }
                    ExceptionDetails value = OBJECTMAPPER.treeToValue(exceptionDetails, ExceptionDetails.class);
                    page.handleException(value);

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        };
        exceptionThrownLis.setMothod("Runtime.exceptionThrown");
        exceptionThrownLis.setTarget(this);
        this.client.on(exceptionThrownLis.getMothod(), exceptionThrownLis);

        DefaultBrowserListener<Object> targetCrashedLis = new DefaultBrowserListener<Object>() {
            @Override
            public void onBrowserEvent(Object event) {
                Page page = (Page) this.getTarget();
                page.onTargetCrashed();
            }
        };
        targetCrashedLis.setMothod("Inspector.targetCrashed");
        targetCrashedLis.setTarget(this);
        this.client.on(targetCrashedLis.getMothod(), targetCrashedLis);

        DefaultBrowserListener<MetricsPayload> metricsLis = new DefaultBrowserListener<MetricsPayload>() {
            @Override
            public void onBrowserEvent(MetricsPayload event) {
                Page page = (Page) this.getTarget();
                try {
                    page.emitMetrics(event);
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                } catch (IntrospectionException e) {
                    e.printStackTrace();
                } catch (InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        };
        metricsLis.setMothod("Inspector.targetCrashed");
        metricsLis.setTarget(this);
        this.client.on(metricsLis.getMothod(), metricsLis);

        DefaultBrowserListener<EntryAddedPayload> entryAddedLis = new DefaultBrowserListener<EntryAddedPayload>() {
            @Override
            public void onBrowserEvent(EntryAddedPayload event) {
                Page page = (Page) this.getTarget();
                page.onLogEntryAdded(event);
            }
        };
        entryAddedLis.setMothod("Log.entryAdded");
        entryAddedLis.setTarget(this);
        this.client.on(entryAddedLis.getMothod(), entryAddedLis);

        DefaultBrowserListener<FileChooserOpenedPayload> fileChooserOpenedLis = new DefaultBrowserListener<FileChooserOpenedPayload>() {
            @Override
            public void onBrowserEvent(FileChooserOpenedPayload event) {
                Page page = (Page) this.getTarget();
                page.onFileChooser(event);
            }
        };
        fileChooserOpenedLis.setMothod("Page.fileChooserOpened");
        fileChooserOpenedLis.setTarget(this);
        this.client.on(fileChooserOpenedLis.getMothod(), fileChooserOpenedLis);

    }

    /**
     * 监听页面的关闭事件
     *
     * @param handler 要提供的处理器
     */
    public void onClose(EventHandler handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_CLOSE.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onConsole(EventHandler<ConsoleMessage> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_CONSOLE.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onDialg(EventHandler<Dialog> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_DIALOG.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onError(EventHandler<Error> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_ERROR.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onFrameattached(EventHandler<Frame> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_FRAMEATTACHED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onFramedetached(EventHandler<Frame> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_FRAMEDETACHED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onFramenavigated(EventHandler<Frame> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_FRAMENAVIGATED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onLoad(EventHandler handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_LOAD.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onMetrics(EventHandler<PageMetrics> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_METRICS.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onPageerror(EventHandler<RuntimeException> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_ERROR.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onPopup(EventHandler<Error> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_POPUP.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onRequest(EventHandler<Request> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_REQUEST.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onRequestfailed(EventHandler<Request> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_REQUESTFAILED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onRequestfinished(EventHandler<Request> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_REQUESTFINISHED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onResponse(EventHandler<Response> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_RESPONSE.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onWorkercreated(EventHandler<Worker> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_WORKERCREATED.getName());
        this.on(listener.getMothod(), listener);
    }

    public void onWorkerdestroyed(EventHandler<Worker> handler) {
        DefaultBrowserListener listener = new DefaultBrowserListener();
        listener.setHandler(handler);
        listener.setMothod(Events.PAGE_WORKERDESTROYED.getName());
        this.on(listener.getMothod(), listener);
    }

    /**
     * 此方法在页面内执行 document.querySelector。如果没有元素匹配指定选择器，返回值是 null。
     *
     * @param selector 选择器
     * @return ElementHandle
     */
    public ElementHandle $(String selector) {
        return this.mainFrame().$(selector);
    }

    /**
     * 此方法在页面内执行 document.querySelectorAll。如果没有元素匹配指定选择器，返回值是 []。
     *
     * @param selector 选择器
     * @return List<ElementHandle>
     */
    public List<ElementHandle> $$(String selector) {
        return this.mainFrame().$$(selector);
    }

    /**
     * 此方法在页面内执行 Array.from(document.querySelectorAll(selector))，然后把匹配到的元素数组作为第一个参数传给 pageFunction。
     *
     * @param selector     一个框架选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @param type
     * @param args         要传给 pageFunction 的参数。（比如你的代码里生成了一个变量，在页面中执行方法时需要用到，可以通过这个 args 传进去）
     * @return pageFunction 的返回值
     */
    public Object $$eval(String selector, String pageFunction, PageEvaluateType type, Object... args) {
        return this.mainFrame().$$eval(selector, pageFunction, type, args);
    }

    /**
     * <p>返回主 Frame</p>
     * 保证页面一直有有一个主 frame
     *
     * @return {@link Frame}
     */
    public Frame mainFrame() {
        return this.frameManager.mainFrame();
    }

    /**
     * 此方法在页面内执行 document.querySelector，然后把匹配到的元素作为第一个参数传给 pageFunction。
     *
     * @param selector     选择器
     * @param pageFunction 在浏览器实例上下文中要执行的方法
     * @param type         具体类型
     * @param args         要传给 pageFunction 的参数。（比如你的代码里生成了一个变量，在页面中执行方法时需要用到，可以通过这个 args 传进去）
     * @return pageFunction 的返回值
     */
    public Object $eval(String selector, String pageFunction, PageEvaluateType type, Object... args) {
        return this.mainFrame().$eval(selector, pageFunction, type, args);
    }

    /**
     * 此方法解析指定的XPath表达式。
     *
     * @param expression XPath表达式。
     * @return ElementHandle
     */
    public List<ElementHandle> $x(String expression) {
        return this.mainFrame().$x(expression);
    }

    /**
     * 注入一个指定src(url)或者代码(content)的 script 标签到当前页面。
     *
     * @param options 可选参数
     * @return 注入完成的tag标签
     * @throws IOException 异常
     */
    public ElementHandle addScriptTag(ScriptTagOptions options) throws IOException {
        return this.mainFrame().addScriptTag(options);
    }

    /**
     * 添加一个指定link(url)的 <link rel="stylesheet"> 标签。 或者添加一个指定代码(content)的 <style type="text/css"> 标签。
     *
     * @param options link标签
     * @return 注入完成的tag标签。当style的onload触发或者代码被注入到frame。
     * @throws IOException 异常
     */
    public ElementHandle addStyleTag(StyleTagOptions options) throws IOException {
        return this.mainFrame().addStyleTag(options);
    }

    /**
     * 为HTTP authentication 提供认证凭据 。
     * <p>
     * 传 null 禁用认证。
     *
     * @param credentials 验证信息
     */
    public void authenticate(Credentials credentials) {
        this.frameManager.networkManager().authenticate(credentials);
    }

    /**
     * 相当于多个tab时，切换到某个tab。
     */
    public void bringToFront() {
        this.client.send("Page.bringToFront", null, true);
    }

    /**
     * 返回页面隶属的浏览器
     *
     * @return
     */
    public Browser browser() {
        return this.target.browser();
    }

    /**
     * 返回默认的浏览器上下文
     *
     * @return 浏览器上下文
     */
    public BrowserContext browserContext() {
        return this.target.browserContext();
    }

    /**
     * 此方法找到一个匹配 selector 选择器的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 点击它。 如果选择器没有匹配任何元素，此方法将会报错。
     *
     * @param selector 选择器
     * @param options  参数
     * @throws InterruptedException 异常
     */
    public void click(String selector, ClickOptions options) throws InterruptedException {
        this.mainFrame().click(selector, options);
    }

    /**
     * page.close() 在 beforeunload 处理之前默认不执行
     * <p><strong>注意 如果 runBeforeUnload 设置为true，可能会弹出一个 beforeunload 对话框。 这个对话框需要通过页面的 'dialog' 事件手动处理</strong></p>
     *
     * @param runBeforeUnload 默认 false. 是否执行 before unload
     */
    public void close(boolean runBeforeUnload) {
        ValidateUtil.assertBoolean(this.client.getConnection() != null, "Protocol error: Connection closed. Most likely the page has been closed.");

        if (runBeforeUnload) {
            this.client.send("Page.close", null, false);
        } else {
            Map<String, Object> params = new HashMap<>();
            params.put("targetId", this.target.getTargetId());
            this.client.getConnection().send("Target.closeTarget", params, true);
            this.target.waitInitializedPromise();
        }
    }

    /**
     * <p>截图</p>
     * 备注 在OS X上 截图需要至少1/6秒。查看讨论：https://crbug.com/741689。
     *
     * @return String
     */
    public String screenshot(ScreenshotOptions options) throws IOException {
        String screenshotType = null;
        // options.type takes precedence over inferring the type from options.path
        // because it may be a 0-length file with no extension created beforehand (i.e. as a temp file).
        if (StringUtil.isNotEmpty(options.getType())) {
            ValidateUtil.assertBoolean("png".equals(options.getType()) || "jpeg".equals(options.getType()), "Unknown options.type value: " + options.getType());
            screenshotType = options.getType();
        } else if (StringUtil.isNotEmpty(options.getPath())) {
            String mimeType = Files.probeContentType(Paths.get(options.getPath()));
            if ("image/png".equals(mimeType))
                screenshotType = "png";
            else if ("image/jpeg".equals(mimeType))
                screenshotType = "jpeg";
            ValidateUtil.assertBoolean(StringUtil.isNotEmpty(screenshotType), "Unsupported screenshot mime type: " + mimeType);
        }

        if (StringUtil.isEmpty(screenshotType))
            screenshotType = "png";
        if (options.getQuality() > 0) {
            ValidateUtil.assertBoolean("jpeg".equals(screenshotType), "options.quality is unsupported for the " + screenshotType + " screenshots");
            ValidateUtil.assertBoolean(options.getQuality() <= 100, "Expected options.quality to be between 0 and 100 (inclusive), got " + options.getQuality());
        }
        ValidateUtil.assertBoolean(options.getClip() == null || !options.getFullPage(), "options.clip and options.fullPage are exclusive");
        if (options.getClip() != null) {
            ValidateUtil.assertBoolean(options.getClip().getWidth() != 0, "Expected options.clip.width not to be 0.");
            ValidateUtil.assertBoolean(options.getClip().getHeight() != 0, "Expected options.clip.height not to be 0.");
        }
        return (String) this.screenshotTaskQueue.postTask((type, op) -> screenshotTask((String) type, (ScreenshotOptions) op), screenshotType, options);
    }

    /**
     * 当提供的选择器完成选中后，触发change和input事件 如果没有元素匹配指定选择器，将报错。
     *
     * @param selector 要查找的选择器
     * @param values   查找的配置项。如果选择器有多个属性，所有的值都会查找，否则只有第一个元素被找到
     * @return
     */
    public List<String> select(String selector, List<String> values) {
        return this.mainFrame().select(selector, values);
    }

    /**
     * 返回页面标题
     *
     * @return 页面标题
     */
    public String title() {
        return this.mainFrame().title();
    }

    /**
     * 设置绕过页面的安全政策
     * <p>注意 CSP 发生在 CSP 初始化而不是评估阶段。也就是说应该在导航到这个域名前设置</p>
     *
     * @param enabled 是否绕过页面的安全政策
     */
    public void setBypassCSP(boolean enabled) {
        Map<String, Object> params = new HashMap<>();
        params.put("enabled", enabled);
        this.client.send("Page.setBypassCSP", params, true);
    }

    /**
     * 设置每个请求忽略缓存。默认是启用缓存的。
     *
     * @param enabled 设置缓存的 enabled 状态
     */
    public void setCacheEnabled(boolean enabled) {
        this.frameManager.networkManager().setCacheEnabled(enabled);
    }

    /**
     * 给页面设置html
     *
     * @param html    分派给页面的HTML。
     * @param options timeout <number> 加载资源的超时时间，默认值为30秒，传入0禁用超时. 可以使用 page.setDefaultNavigationTimeout(timeout) 或者 page.setDefaultTimeout(timeout) 方法修改默认值
     *                waitUntil <string|Array<string>> HTML设置成功的标志事件, 默认为 load。 如果给定的是一个事件数组，那么当所有事件之后，给定的内容才被认为设置成功。 事件可以是：
     *                load - load事件触发后，设置HTML内容完成。
     *                domcontentloaded - DOMContentLoaded 事件触发后，设置HTML内容完成。
     *                networkidle0 - 不再有网络连接时（至少500毫秒之后），设置HTML内容完成
     *                networkidle2 - 只剩2个网络连接时（至少500毫秒之后），设置HTML内容完成
     */
    public void setContent(String html, PageNavigateOptions options) {
        this.frameManager.mainFrame().setContent(html, options);
    }

    /**
     * 获取指定url的cookies
     *
     * @param urls 指定的url集合
     * @return Cookie
     */
    public List<Cookie> cookies(List<String> urls) {
        Map<String, Object> params = new HashMap<>();
        params.put("urls", ValidateUtil.isNotEmpty(urls) ? urls : this.url());
        JsonNode result = this.client.send("Network.getCookies", params, true);
        JsonNode cookiesNode = result.get("cookies");
        Iterator<JsonNode> elements = cookiesNode.elements();
        List<Cookie> cookies = new ArrayList<>();
        while (elements.hasNext()) {
            JsonNode cookieNode = elements.next();
            Cookie cookie = null;
            try {
                cookie = OBJECTMAPPER.treeToValue(cookieNode, Cookie.class);
                cookie.setPriority(null);
                cookies.add(cookie);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        return cookies;
    }

    public void setCookie(List<CookieParam> cookies) {
        String pageURL = this.url();
        boolean startsWithHTTP = pageURL.startsWith("http");
        cookies.replaceAll(cookie -> {
            if (StringUtil.isEmpty(cookie.getUrl()) && startsWithHTTP)
                cookie.setUrl(pageURL);
            ValidateUtil.assertBoolean(!ABOUT_BLANK.equals(cookie.getUrl()), "Blank page can not have cookie " + cookie.getName());
            ValidateUtil.assertBoolean(!StringUtil.isNotEmpty(cookie.getUrl()) && !cookie.getUrl().startsWith("data:"), "Data URL page can not have cookie " + cookie.getName());
            return cookie;
        });
        List<DeleteCookiesParameters> deleteCookiesParameters = new ArrayList<>();
        for (CookieParam cookie : cookies) {
            deleteCookiesParameters.add(new DeleteCookiesParameters(cookie.getName(), cookie.getUrl(), cookie.getDomain(), cookie.getPath()));
        }

        this.deleteCookie(deleteCookiesParameters);
        Map<String, Object> params = new HashMap<>();
        params.put("cookies", cookies);
        this.client.send("Network.setCookies", params, true);
    }

    /**
     * 此方法会改变下面几个方法的默认30秒等待时间：
     * ${@link Page#goTo(String)}
     * ${@link Page#goTo(String, PageNavigateOptions)}
     * ${@link Page#goBack(PageNavigateOptions)}
     * ${@link Page#goForward(PageNavigateOptions)}
     * ${@link Page#reload(PageNavigateOptions)}
     * ${@link Page#waitForNavigation(PageNavigateOptions)}
     *
     * @param timeout
     */
    public void setDefaultNavigationTimeout(int timeout) {
        this.timeoutSettings.setDefaultNavigationTimeout(timeout);
    }

    /**
     * 当前页面发起的每个请求都会带上这些请求头
     * 注意 此方法不保证请求头的顺序
     *
     * @param headers 每个 HTTP 请求都会带上这些请求头。值必须是字符串
     */
    public void setExtraHTTPHeaders(Map<String, String> headers) {
        this.frameManager.networkManager().setExtraHTTPHeaders(headers);
    }

    /**
     * Sets the page's geolocation.
     *
     * @param longitude Latitude between -90 and 90.
     * @param latitude  Longitude between -180 and 180.
     * @param accuracy  Optional non-negative accuracy value.
     */
    public void setGeolocation(int longitude, int latitude, int accuracy) {

        if (longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Invalid longitude " + longitude + ": precondition -180 <= LONGITUDE <= 180 failed.");
        if (latitude < -90 || latitude > 90)
            throw new IllegalArgumentException("Invalid latitude " + latitude + ": precondition -90 <= LATITUDE <= 90 failed.");
        if (accuracy < 0)
            throw new IllegalArgumentException("Invalid accuracy " + accuracy + ": precondition 0 <= ACCURACY failed.");
        Map<String, Object> params = new HashMap<>();
        params.put("longitude", longitude);
        params.put("latitude", latitude);
        params.put("accuracy", accuracy);
        this.client.send("Emulation.setGeolocationOverride", params, true);
    }

    /**
     * 是否启用js
     * 注意 改变这个值不会影响已经执行的js。下一个跳转会完全起作用。
     *
     * @param enabled 是否启用js
     */
    public void setJavaScriptEnabled(boolean enabled) {
        if (this.javascriptEnabled == enabled)
            return;
        this.javascriptEnabled = enabled;
        Map<String, Object> params = new HashMap<>();
        params.put("value", !enabled);
        this.client.send("Emulation.setScriptExecutionDisabled", params, true);
    }

    /**
     * 设置启用离线模式。
     *
     * @param enabled 设置 true, 启用离线模式。
     */
    public void setOfflineMode(boolean enabled) {
        this.frameManager.networkManager().setOfflineMode(enabled);
    }

    /**
     * 启用请求拦截器，会激活 request.abort, request.continue 和 request.respond 方法。这提供了修改页面发出的网络请求的功能。
     * <p></p>
     * 一旦启用请求拦截，每个请求都将停止，除非它继续，响应或中止
     *
     * @param value 是否启用请求拦截器
     */
    public void setRequestInterception(boolean value) {
        this.frameManager.networkManager().setRequestInterception(value);
    }

    private String screenshotTask(String format, ScreenshotOptions options) {
        Map<String, Object> params = new HashMap<>();
        params.put("targetId", this.target.getTargetId());
        this.client.send("Target.activateTarget", params, true);
        ClipOverwrite clip = null;
        if (options.getClip() != null) {
            clip = processClip(options.getClip());
        }
        if (options.getFullPage()) {
            JsonNode metrics = this.client.send("Page.getLayoutMetrics", null, true);
            double width = Math.ceil(metrics.get("contentSize").get("width").asDouble());
            double height = Math.ceil(metrics.get("contentSize").get("height").asDouble());
            clip = new ClipOverwrite(0, 0, width, height, 1);
            ScreenOrientation screenOrientation = null;
            if (this.viewport.getIsLandscape()) {
                screenOrientation = new ScreenOrientation(90, "landscapePrimary");
            } else {
                screenOrientation = new ScreenOrientation(0, "portraitPrimary");
            }
            params.clear();
            params.put("mobile", this.viewport.getIsMobile());
            params.put("width", width);
            params.put("height", height);
            params.put("deviceScaleFactor", this.viewport.getDeviceScaleFactor());
            params.put("screenOrientation", screenOrientation);
            this.client.send("Emulation.setDeviceMetricsOverride", params, true);
        }
        boolean shouldSetDefaultBackground = options.getOmitBackground() && "png".equals(format);
        if (shouldSetDefaultBackground) {
            params.clear();
            Map<String, Integer> colorMap = new HashMap<>();
            colorMap.put("r", 0);
            colorMap.put("g", 0);
            colorMap.put("b", 0);
            colorMap.put("a", 0);
            params.put("color", colorMap);
            this.client.send("Emulation.setDefaultBackgroundColorOverride", params, true);
        }
        params.clear();
        params.put("format", format);
        params.put("quality", options.getQuality());
        params.put("clip", clip);
        JsonNode result = this.client.send("Page.captureScreenshot", params, true);
        if (shouldSetDefaultBackground) {
            this.client.send("Emulation.setDefaultBackgroundColorOverride", null, true);
        }
        if (options.getFullPage() && this.viewport != null)
            this.setViewport(this.viewport);
        BASE64Decoder decoder = new BASE64Decoder();
        String data = result.get("data").asText();
        try {
//            byte[] buffer = decoder.decodeBuffer(data);
            byte[] buffer = Base64.decode(data);
            if (StringUtil.isNotEmpty(options.getPath())) {
                //TODO 验证
                Files.write(Paths.get(options.getPath()), buffer, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return data;
    }

    private ClipOverwrite processClip(Clip clip) {
        long x = Math.round(clip.getX());
        long y = Math.round(clip.getY());
        long width = Math.round(clip.getWidth() + clip.getX() - x);
        long height = Math.round(clip.getHeight() + clip.getY() - y);
        ClipOverwrite result = new ClipOverwrite(x, y, width, height, 1);
        return result;
    }

    private void onFileChooser(FileChooserOpenedPayload event) {
        if (ValidateUtil.isEmpty(this.fileChooserInterceptors))
            return;
        Frame frame = this.frameManager.frame(event.getFrameId());
        ExecutionContext context = frame.executionContext();
        ElementHandle element = context.adoptBackendNodeId(event.getBackendNodeId());
        Set<BiConsumer<Object, FileChooser>> interceptors = new HashSet<>(this.fileChooserInterceptors);
        this.fileChooserInterceptors.clear();
        FileChooser fileChooser = new FileChooser(this.client, element, event);
        for (BiConsumer interceptor : interceptors)
            interceptor.accept(null, fileChooser);
    }

    private void onLogEntryAdded(EntryAddedPayload event) {
        if (ValidateUtil.isNotEmpty(event.getEntry().getArgs()))
            event.getEntry().getArgs().forEach(arg -> Helper.releaseObject(this.client, arg));
        if (!"worker".equals(event.getEntry().getSource()))
            this.emit(Events.PAGE_CONSOLE.getName(), new ConsoleMessage(event.getEntry().getLevel(), event.getEntry().getText(), Collections.emptyList(), new Location(event.getEntry().getUrl(), event.getEntry().getLineNumber())));
    }

    private void emitMetrics(MetricsPayload event) throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        PageMetrics pageMetrics = new PageMetrics();
        Metrics metrics = this.buildMetricsObject(event.getMetrics());
        pageMetrics.setMetrics(metrics);
        pageMetrics.setTitle(event.getTitle());
        this.emit(Events.PAGE_METRICS.getName(), pageMetrics);
    }

    private void onTargetCrashed() {
        this.emit("error", new PageCrashException("Page crashed!"));
    }

    /**
     * 创建一个page对象
     *
     * @param client
     * @param target
     * @param ignoreHTTPSErrors
     * @param viewport
     * @param screenshotTaskQueue
     * @return
     */
    public static Page create(CDPSession client, Target target, boolean ignoreHTTPSErrors, Viewport viewport, TaskQueue screenshotTaskQueue) {
        Page page = new Page(client, target, ignoreHTTPSErrors, screenshotTaskQueue);
        page.initialize();
        if (viewport != null) {
            page.setViewport(viewport);
        }
        return page;
    }

    /**
     * 当js对话框出现的时候触发，比如alert, prompt, confirm 或者 beforeunload。Puppeteer可以通过Dialog's accept 或者 dismiss来响应弹窗。
     *
     * @param event 触发事件
     */
    private void onDialog(JavascriptDialogOpeningPayload event) {
        DialogType dialogType = null;
        if ("alert".equals(event.getType()))
            dialogType = DialogType.Alert;
        else if ("confirm".equals(event.getType()))
            dialogType = DialogType.Confirm;
        else if ("prompt".equals(event.getType()))
            dialogType = DialogType.Prompt;
        else if ("beforeunload".equals(event.getType()))
            dialogType = DialogType.BeforeUnload;
        ValidateUtil.assertBoolean(dialogType != null, "Unknown javascript dialog type: " + event.getType());
        Dialog dialog = new Dialog(this.client, dialogType, event.getMessage(), event.getDefaultPrompt());
        this.emit(Events.PAGE_DIALOG.getName(), dialog);
    }

    private void onConsoleAPI(ConsoleAPICalledPayload event) {
        if (event.getExecutionContextId() == 0) {
            // DevTools protocol stores the last 1000 console messages. These
            // messages are always reported even for removed execution contexts. In
            // this case, they are marked with executionContextId = 0 and are
            // reported upon enabling Runtime agent.
            //
            // Ignore these messages since:
            // - there's no execution context we can use to operate with message
            //   arguments
            // - these messages are reported before Puppeteer clients can subscribe
            //   to the 'console'
            //   page event.
            //
            // @see https://github.com/puppeteer/puppeteer/issues/3865
            return;
        }
        ExecutionContext context = this.frameManager.executionContextById(event.getExecutionContextId());
        List<JSHandle> values = new ArrayList<>();
        for (int i = 0; i < event.getArgs().size(); i++) {
            RemoteObject arg = event.getArgs().get(i);
            JSHandle.createJSHandle(context, arg);
        }
        ;
        this.addConsoleMessage(event.getType(), values, event.getStackTrace());
    }

    private void onBindingCalled(BindingCalledPayload event) {
        String payload = event.getPayload();
        JsonNode payloadNode = OBJECTMAPPER.valueToTree(payload);
        List<JsonNode> jsonNodes = new ArrayList<>();
        Iterator<JsonNode> args = payloadNode.get("args").elements();
        while (args.hasNext()) {
            jsonNodes.add(args.next());
        }
        String expression = null;
        try {
            Object result = this.pageBindings.get(event.getName()).apply(jsonNodes);
            expression = Helper.evaluationString(deliverResult(), PageEvaluateType.FUNCTION, payloadNode.get("name").toString(), payloadNode.get("seq").toString(), result);
        } catch (Exception e) {
            expression = Helper.evaluationString(deliverError(), PageEvaluateType.FUNCTION, payloadNode.get("name").toString(), payloadNode.get("seq").toString(), e, e.getMessage());
        }
        Map<String, Object> params = new HashMap<>();
        params.put("expression", expression);
        params.put("contextId", event.getExecutionContextId());
        this.client.send("Runtime.evaluate", params, false);
    }

    private String deliverError() {
        return "function deliverError(name, seq, message, stack) {\n" +
                "      const error = new Error(message);\n" +
                "      error.stack = stack;\n" +
                "      window[name]['callbacks'].get(seq).reject(error);\n" +
                "      window[name]['callbacks'].delete(seq);\n" +
                "    }";
    }

    private String deliverResult() {
        return "function deliverResult(name, seq, result) {\n" +
                "      window[name]['callbacks'].get(seq).resolve(result);\n" +
                "      window[name]['callbacks'].delete(seq);\n" +
                "    }";
    }

    /**
     * 如果是一个浏览器多个页面的情况，每个页面都可以有单独的viewport
     * <p>注意 在大部分情况下，改变 viewport 会重新加载页面以设置 isMobile 或者 hasTouch</p>
     *
     * @param viewport
     */
    public void setViewport(Viewport viewport) {
        boolean needsReload = this.emulationManager.emulateViewport(viewport);
        this.viewport = viewport;
        if (needsReload)
            this.reload(null);
    }

    public void initialize() {
        frameManager.initialize();
        Map<String, Object> params = new HashMap<>();
        params.put("autoAttach", true);
        params.put("waitForDebuggerOnStart", false);
        params.put("flatten", true);
        this.client.send("Target.setAutoAttach", params, false);
        params.clear();
        this.client.send("Performance.enable", params, false);
        this.client.send("Log.enable", params, true);
    }

    private void addConsoleMessage(String type, List<JSHandle> args, StackTrace stackTrace) {
        if (this.getListenerCount(Events.PAGE_CONSOLE.getName()) == 0) {
            args.forEach(arg -> arg.dispose());
            return;
        }
        List<String> textTokens = new ArrayList();
        for (JSHandle arg : args) {
            RemoteObject remoteObject = arg.getRemoteObject();
            if (StringUtil.isNotEmpty(remoteObject.getObjectId()))
                textTokens.add(arg.toString());
            else {
                try {
                    textTokens.add(Constant.OBJECTMAPPER.writeValueAsString(Helper.valueFromRemoteObject(remoteObject)));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        Location location = stackTrace != null && stackTrace.getCallFrames().size() > 0 ? new Location(stackTrace.getCallFrames().get(0).getUrl(), stackTrace.getCallFrames().get(0).getLineNumber(), stackTrace.getCallFrames().get(0).getColumnNumber()) : new Location();
        ConsoleMessage message = new ConsoleMessage(type, String.join(" ", textTokens), args, location);
        this.emit(Events.PAGE_CONSOLE.getName(), message);
    }

    private void handleException(ExceptionDetails exceptionDetails) {
        String message = Helper.getExceptionMessage(exceptionDetails);
        RuntimeException err = new RuntimeException(message);
//        err.setStackTrace(null); // Don't report clientside error with a node stack attached
        this.emit(Events.PAGE_PageError.getName(), err);
    }

    /**
     * 返回页面的完整 html 代码，包括 doctype。
     *
     * @return 页面内容
     */
    public String content() {
        return this.frameManager.getMainFrame().content();
    }

    /**
     * <p>导航到指定的url,因为goto是java的关键字，所以就采用了goTo方法名
     *
     * <p>以下情况此方法将报错：
     * <p>发生了 SSL 错误 (比如有些自签名的https证书).
     * <p>目标地址无效
     * <p>超时
     * <p>主页面不能加载
     *
     * @param url      url
     * @param options: <p>timeout <number> 跳转等待时间，单位是毫秒, 默认是30秒, 传 0 表示无限等待。可以通过page.setDefaultNavigationTimeout(timeout)方法修改默认值
     *                 <p>waitUntil <string|Array<string>> 满足什么条件认为页面跳转完成，默认是 load 事件触发时。指定事件数组，那么所有事件触发后才认为是跳转完成。事件包括：
     *                 <p>load - 页面的load事件触发时
     *                 <p>domcontentloaded - 页面的 DOMContentLoaded 事件触发时
     *                 <p>networkidle0 - 不再有网络连接时触发（至少500毫秒后）
     *                 <p>networkidle2 - 只有2个网络连接时触发（至少500毫秒后）
     *                 <p>referer <string> Referer header value. If provided it will take preference over the referer header value set by page.setExtraHTTPHeaders().
     * @return Response
     */
    public Response goTo(String url, PageNavigateOptions options) {
        return this.frameManager.getMainFrame().goTo(url, options);
    }

    /**
     * 导航到某个网站
     * <p>以下情况此方法将报错：</p>
     * <p>发生了 SSL 错误 (比如有些自签名的https证书).</p>
     * <p>目标地址无效</p>
     * <p>超时</p>
     * <p>主页面不能加载</p>
     *
     * @param url 导航到的地址. 地址应该带有http协议, 比如 https://.
     * @return 响应
     */
    public Response goTo(String url) {
        return this.frameManager.getMainFrame().goTo(url, null);
    }

    /**
     * 删除cookies
     *
     * @param cookies 要删除的ciikies
     */
    public void deleteCookie(List<DeleteCookiesParameters> cookies) {
        String pageURL = this.url();
        for (DeleteCookiesParameters cookie : cookies) {
            if (StringUtil.isEmpty(cookie.getUrl()) && pageURL.startsWith("http"))
                cookie.setUrl(pageURL);
            Map<String, Object> params = getProperties(cookie);
            this.client.send("Network.deleteCookies", params, true);
        }
    }

    private Map<String, Object> getProperties(DeleteCookiesParameters cookie) {
        Map<String, Object> params = new HashMap<>();
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(cookie.getClass());
            PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
            for (PropertyDescriptor descriptor : propertyDescriptors) {
                params.put(descriptor.getName(), descriptor.getReadMethod().invoke(cookie));
            }
        } catch (IntrospectionException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return params;
    }

    /**
     * 根据指定的参数和 user agent 生成模拟器。此方法是和下面两个方法效果相同
     * <p>${@link Page#setViewport(Viewport)}<p/>
     * <p>${@link Page#setUserAgent(String)}<p/>
     *
     * @param options Device 模拟器枚举类
     */
    public void emulate(Device options) {
        this.setViewport(options.getViewport());
        this.setUserAgent(options.getUserAgent());
    }

    /**
     * 给页面设置userAgent
     *
     * @param userAgent userAgent的值
     */
    private void setUserAgent(String userAgent) {
        this.frameManager.networkManager().setUserAgent(userAgent);
    }

    /**
     * 改变页面的css媒体类型。支持的值仅包括 'screen', 'print' 和 null。传 null 禁用媒体模拟
     *
     * @param type css媒体类型
     */
    public void emulateMediaType(String type) {
        this.emulateMedia(type);
    }

    /**
     * 此方法找到一个匹配的元素，如果需要会把此元素滚动到可视，然后通过 page.touchscreen 来点击元素的中间位置 如果没有匹配的元素，此方法会报错
     *
     * @param selector 要点击的元素的选择器。如果有多个匹配的元素，点击第一个
     */
    public void tap(String selector) {
        this.mainFrame().tap(selector);
    }


    public void emulateTimezone(String timezoneId) {
        try {
            Map<String, Object> params = new HashMap<>();
            if (timezoneId == null) {
                timezoneId = "";
            }
            params.put("timezoneId", timezoneId);
            this.client.send("Emulation.setTimezoneOverride", params, true);
        } catch (Exception e) {
            if (e.getMessage().contains("Invalid timezone"))
                throw new IllegalArgumentException("Invalid timezone ID: " + timezoneId);
            throw e;
        }
    }

    public void evaluateOnNewDocument(String pageFunction, PageEvaluateType type, Object... args) {
        Map<String, Object> params = new HashMap<>();
        if (PageEvaluateType.STRING.equals(type)) {
            ValidateUtil.assertBoolean(args.length == 0, "Cannot evaluate a string with arguments");
            params.put("source", pageFunction);
            this.client.send("Page.addScriptToEvaluateOnNewDocument", params, true);
        } else {
            List<Object> objects = Arrays.asList(args);
            List<String> argsList = new ArrayList<>();
            objects.forEach(arg -> {
                if (arg == null) {
                    argsList.add("undefined");
                } else {
                    try {
                        argsList.add(OBJECTMAPPER.writeValueAsString(arg));
                    } catch (JsonProcessingException e) {
                        argsList.add("undefined");
                    }
                }
            });
            String source = "(" + pageFunction + ")" + String.join(",", argsList);
            params.put("source", source);
            this.client.send("Page.addScriptToEvaluateOnNewDocument", params, true);
        }
    }

    /**
     * 此方法添加一个命名为 name 的方法到页面的 window 对象 当调用 name 方法时，在 node.js 中执行 puppeteerFunction
     *
     * @param name              挂载到window对象的方法名
     * @param puppeteerFunction 调用name方法时实际执行的方法
     */
    public void exposeFunction(String name, Function<List<JsonNode>, Object> puppeteerFunction) {
        if (this.pageBindings.containsKey(name)) {
            throw new IllegalArgumentException(MessageFormat.format("Failed to add page binding with name {0}: window['{1}'] already exists!", name, name));
        }
        this.pageBindings.put(name, puppeteerFunction);
        String expression = Helper.evaluationString(addPageBinding(), PageEvaluateType.FUNCTION, name);
        Map<String, Object> params = new HashMap<>();
        params.put("name", name);
        this.client.send("Runtime.addBinding", params, true);
        params.clear();
        params.put("source", expression);
        this.client.send("Page.addScriptToEvaluateOnNewDocument", params, true);
        this.frames().forEach(frame -> {
            frame.evaluate(expression, PageEvaluateType.FUNCTION);
        });
    }

    private String addPageBinding() {
        return "function addPageBinding(bindingName) {\n" +
                "      const win = /** @type * */ (window);\n" +
                "      const binding = /** @type function(string):* */ (win[bindingName]);\n" +
                "\n" +
                "      win[bindingName] = (...args) => {\n" +
                "        const me = window[bindingName];\n" +
                "        let callbacks = me['callbacks'];\n" +
                "        if (!callbacks) {\n" +
                "          callbacks = new Map();\n" +
                "          me['callbacks'] = callbacks;\n" +
                "        }\n" +
                "        const seq = (me['lastSeq'] || 0) + 1;\n" +
                "        me['lastSeq'] = seq;\n" +
                "        const promise = new Promise((resolve, reject) => callbacks.set(seq, {resolve, reject}));\n" +
                "        binding(JSON.stringify({name: bindingName, seq, args}));\n" +
                "        return promise;\n" +
                "      };\n" +
                "    }";
    }

    /**
     * 此方法找到一个匹配selector的元素，并且把焦点给它。 如果没有匹配的元素，此方法将报错。
     *
     * @param selector 要给焦点的元素的选择器selector。如果有多个匹配的元素，焦点给第一个元素。
     * @throws JsonProcessingException 异常
     */
    public void focus(String selector) {
        this.mainFrame().focus(selector);
    }

    /**
     * 返回加载到页面中的所有iframe标签
     *
     * @return iframe标签
     */
    public List<Frame> frames() {
        return this.frameManager.frames();
    }

    /**
     * 导航到页面历史的前一个页面
     * <p>options 的 referer参数不用填，填了也用不上</p>
     * <p>
     * options 导航配置，可选值：
     * <p>otimeout  跳转等待时间，单位是毫秒, 默认是30秒, 传 0 表示无限等待。可以通过page.setDefaultNavigationTimeout(timeout)方法修改默认值
     * <p>owaitUntil 满足什么条件认为页面跳转完成，默认是load事件触发时。指定事件数组，那么所有事件触发后才认为是跳转完成。事件包括：
     * <p>oload - 页面的load事件触发时
     * <p>odomcontentloaded - 页面的DOMContentLoaded事件触发时
     * <p>onetworkidle0 - 不再有网络连接时触发（至少500毫秒后）
     * <p>onetworkidle2 - 只有2个网络连接时触发（至少500毫秒后）
     *
     * @param options 见上面注释
     * @return 响应
     */
    public Response goBack(PageNavigateOptions options) {
        return this.go(-1, options);
    }

    /**
     * 导航到页面历史的后一个页面。
     * <p>options 的 referer参数不用填，填了也用不上</p>
     *
     * @param options
     * @return Response
     */
    public Response goForward(PageNavigateOptions options) {
        return this.go(+1, options);
    }

    /**
     * 此方法找到一个匹配的元素，如果需要会把此元素滚动到可视，然后通过 page.mouse 来hover到元素的中间。 如果没有匹配的元素，此方法将会报错。
     *
     * @param selector 要hover的元素的选择器。如果有多个匹配的元素，hover第一个。
     */
    public void hover(String selector) {
        this.mainFrame().hover(selector);
    }

    /**
     * 表示页面是否被关闭。
     *
     * @return 页面是否被关闭。
     */
    public boolean isClosed() {
        return this.closed;
    }

    public Keyboard keyboard() {
        return this.keyboard;
    }

    /**
     * 返回页面的一些基本信息
     *
     * @return Metrics 基本信息载体
     * @throws IllegalAccessException    异常
     * @throws IntrospectionException    异常
     * @throws InvocationTargetException 异常
     */
    public Metrics metrics() throws IllegalAccessException, IntrospectionException, InvocationTargetException {
        JsonNode responseNode = this.client.send("Performance.getMetrics", null, true);
        List<Metric> metrics = new ArrayList<>();
        Iterator<JsonNode> elements = responseNode.get("metrics").elements();
        while (elements.hasNext()) {
            JsonNode next = elements.next();
            try {
                Metric value = OBJECTMAPPER.treeToValue(next, Metric.class);
                metrics.add(value);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        return this.buildMetricsObject(metrics);
    }

    /**
     * 生成当前页面的pdf格式，带着 pring css media。如果要生成带着 screen media的pdf，在page.pdf() 前面先调用 page.emulateMedia('screen')
     * <p><strong>注意 目前仅支持无头模式的 Chrome</strong></p>
     *
     * @param options
     * @throws IOException
     */
    public void pdf(PDFOptions options) throws IOException {
        double paperWidth = 8.5;
        double paperHeight = 11;
        if (StringUtil.isNotEmpty(options.getFormat())) {
            PaperFormats format = PaperFormats.valueOf(options.getFormat().toLowerCase());
            ValidateUtil.assertBoolean(format != null, "Unknown paper format: " + options.getFormat());
            paperWidth = format.getWidth();
            paperHeight = format.getHeight();
        } else {
            Double width = convertPrintParameterToInches(options.getWidth());
            if (width != null) {
                paperWidth = width;
            }
            Double height = convertPrintParameterToInches(options.getHeight());
            if (height != null) {
                paperHeight = height;
            }
        }
        Margin margin = options.getMargin();
        Number marginTop, marginLeft, marginBottom, marginRight;
        if ((marginTop = convertPrintParameterToInches(margin.getTop())) == null) {
            marginTop = 0;
        }
        if ((marginLeft = convertPrintParameterToInches(margin.getLeft())) == null) {
            marginLeft = 0;
        }

        if ((marginBottom = convertPrintParameterToInches(margin.getBottom())) == null) {
            marginBottom = 0;
        }

        if ((marginRight = convertPrintParameterToInches(margin.getRight())) == null) {
            marginRight = 0;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("transferMode", "ReturnAsStream");
        params.put("landscape", options.getLandscape());
        params.put("displayHeaderFooter", options.getDisplayHeaderFooter());
        params.put("headerTemplate", options.getHeaderTemplate());
        params.put("footerTemplate", options.getFooterTemplate());
        params.put("printBackground", options.getPrintBackground());
        params.put("scale", options.getScale());
        params.put("paperWidth", paperWidth);
        params.put("paperHeight", paperHeight);
        params.put("marginTop", marginTop);
        params.put("marginBottom", marginBottom);
        params.put("marginLeft", marginLeft);
        params.put("marginRight", marginRight);
        params.put("pageRanges", options.getPageRanges());
        params.put("preferCSSPageSize", options.getPreferCSSPageSize());
        JsonNode result = this.client.send("Page.printToPDF", params, true);
        if (result != null)
            Helper.readProtocolStream(this.client, result.get(RECV_MESSAGE_STREAM_PROPERTY).asText(), options.getPath());
    }

    /**
     * 此方法会改变下面几个方法的默认30秒等待时间：
     * ${@link Page#goTo(String)}
     * ${@link Page#goTo(String, PageNavigateOptions)}
     * ${@link Page#goBack(PageNavigateOptions)}
     * ${@link Page#goForward(PageNavigateOptions)}
     * ${@link Page#reload(PageNavigateOptions)}
     * ${@link Page#waitForNavigation(PageNavigateOptions)}
     *
     * @param timeout
     */
    public void setDefaultTimeout(int timeout) {
        this.timeoutSettings.setDefaultTimeout(timeout);
    }

    /**
     * 此方法遍历js堆栈，找到所有带有指定原型的对象
     *
     * @param prototypeHandle
     * @return
     */
    public JSHandle queryObjects(JSHandle prototypeHandle) {
        ExecutionContext context = this.mainFrame().executionContext();
        return context.queryObjects(prototypeHandle);
    }

    private Double convertPrintParameterToInches(String parameter) {
        if (StringUtil.isEmpty(parameter)) {
            return null;
        }
        double pixels;
        if (Helper.isNumber(parameter)) {
            pixels = Double.parseDouble(parameter);
        } else if (parameter.endsWith("px") || parameter.endsWith("in") || parameter.endsWith("cm") || parameter.endsWith("mm")) {

            String unit = parameter.substring(parameter.length() - 2).toLowerCase();
            String valueText = "";
            if (unitToPixels.containsKey(unit)) {
                valueText = parameter.substring(0, parameter.length() - 2);
            } else {
                // In case of unknown unit try to parse the whole parameter as number of pixels.
                // This is consistent with phantom's paperSize behavior.
                unit = "px";
                valueText = parameter;
            }
            Double value = Double.parseDouble(valueText);
            ValidateUtil.assertBoolean(!Double.isNaN(value), "Failed to parse parameter value: " + parameter);
            pixels = value * unitToPixels.get(unit);
        } else {
            throw new IllegalArgumentException("page.pdf() Cannot handle parameter type: " + parameter);
        }
        return pixels / 96;
    }

    /**
     * 重新加载页面
     *
     * @param options 与${@link Page#goTo(String, PageNavigateOptions)}中的options是一样的配置
     * @return 响应
     */
    public Response reload(PageNavigateOptions options) {
        Response response = this.waitForNavigation(options);
        this.client.send("Page.reload", null, true);
        return response;
    }

    private Metrics buildMetricsObject(List<Metric> metrics) throws IntrospectionException, InvocationTargetException, IllegalAccessException {
        Metrics result = new Metrics();
        BeanInfo beanInfo = Introspector.getBeanInfo(Metric.class);
        PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
        Map<String, PropertyDescriptor> propertyMap = new HashMap<>();
        for (int i = 0; i < propertyDescriptors.length; i++) {
            propertyMap.put(propertyDescriptors[i].getName(), propertyDescriptors[i]);
        }
        if(ValidateUtil.isNotEmpty(metrics)){
            for (Metric metric : metrics) {
                if (supportedMetrics.contains(metric.getName())) {
                    propertyMap.get(metric.getName()).getWriteMethod().invoke(result, metric.getValue());
                }
            }
        }
        return result;
    }

    private Response go(int delta, PageNavigateOptions options) {
        JsonNode historyNode = this.client.send("Page.getNavigationHistory", null, true);
        GetNavigationHistoryReturnValue history = null;
        try {
            history = OBJECTMAPPER.treeToValue(historyNode, GetNavigationHistoryReturnValue.class);
        } catch (JsonProcessingException e) {
            throw new NavigateException(e);
        }
        NavigationEntry entry = history.getEntries().get(history.getCurrentIndex() + delta);
        if (entry == null)
            return null;
        Response response = this.waitForNavigation(options);
        Map<String, Object> params = new HashMap<>();
        params.put("entryId", entry.getId());
        this.client.send("Page.navigateToHistoryEntry", params, true);
        return response;
    }

    /**
     * 此方法在页面跳转到一个新地址或重新加载时解析，如果你的代码会间接引起页面跳转，这个方法比较有用
     * <p>比如你在在代码中使用了Page.click()方法，引起了页面跳转
     * 注意 通过 History API 改变地址会认为是一次跳转。
     *
     * @param options PageNavigateOptions
     * @return 响应
     */
    private Response waitForNavigation(PageNavigateOptions options) {
        return this.frameManager.mainFrame().waitForNavigation(options);
    }

    /**
     * 在页面实例上下文中执行方法
     * 添加一个方法，在以下某个场景被调用：</p>
     * <p>   页面导航完成后</p>
     * <p>   页面的iframe加载或导航完成。这种场景，指定的函数被调用的上下文是新加载的iframe。</p>
     * 指定的函数在所属的页面被创建并且所属页面的任意 script 执行之前被调用。常用于修改页面js环境，比如给 Math.random 设定种子
     *
     * @param pageFunction 要在页面实例上下文中执行的方法
     * @param type         是方法字符串还是普通字符串
     * @param args         要在页面实例上下文中执行的方法的参数
     */
    public void evaluate(String pageFunction, PageEvaluateType type, Object... args) {
        this.frameManager.mainFrame().evaluate(pageFunction, type, args);
    }

    /**
     * 此方法和 page.evaluate 的唯一区别是此方法返回的是页内类型(JSHandle)
     *
     * @param pageFunction 要在页面实例上下文中执行的方法
     * @param type         是方法字符串还是普通字符串
     * @param args         要在页面实例上下文中执行的方法的参数
     * @return
     */
    public JSHandle evaluateHandle(String pageFunction, PageEvaluateType type, Object... args) {
        ExecutionContext context = this.mainFrame().executionContext();
        return (JSHandle) context.evaluateHandle(pageFunction, type, args);
    }

    /**
     * 改变页面的css媒体类型。支持的值仅包括 'screen', 'print' 和 null。传 null 禁用媒体模拟
     *
     * @param type css媒体类型
     */
    public void emulateMedia(String type) {
        ValidateUtil.assertBoolean("screen".equals(type) || "print".equals(type) || type == null, "Unsupported media type: " + type);
        Map<String, Object> params = new HashMap<>();
        params.put("media", type);
        this.client.send("Emulation.setEmulatedMedia", params, true);
    }

    public void emulateMediaFeatures(List<MediaFeature> features) {
        Pattern pattern = Pattern.compile("^prefers-(?:color-scheme|reduced-motion)$");
        Map<String, Object> params = new HashMap<>();
        if (features == null) {
            params.put("features", null);
            this.client.send("Emulation.setEmulatedMedia", params, true);
        }

        features.forEach(mediaFeature -> {
            String name = mediaFeature.getName();
            ValidateUtil.assertBoolean(pattern.matcher(name).find(), "Unsupported media feature: " + name);
        });
        params.put("features", features);
        this.client.send("Emulation.setEmulatedMedia", params, true);
    }

    /**
     * 此方法根据第一个参数的不同有不同的结果：
     *
     * <p>如果 selectorOrFunctionOrTimeout 是 string, 那么认为是 css 选择器或者一个xpath, 根据是不是'//'开头, 这时候此方法是 page.waitForSelector 或 page.waitForXPath的简写</p>
     * <p>如果 selectorOrFunctionOrTimeout 是 function, 那么认为是一个predicate，这时候此方法是page.waitForFunction()的简写</p>
     * <p>如果 selectorOrFunctionOrTimeout 是 number, 那么认为是超时时间，单位是毫秒，返回的是Promise对象,在指定时间后resolve</p>
     * <p>否则会报错
     *
     * @param selectorOrFunctionOrTimeout 选择器, 方法 或者 超时时间
     * @param type                        具体类型
     * @param options                     可选的等待参数
     * @param args                        传给 pageFunction 的参数
     * @return
     */
    public JSHandle waitFor(String selectorOrFunctionOrTimeout, PageEvaluateType type, WaitForSelectorOptions options, Object... args) {
        return this.mainFrame().waitFor(selectorOrFunctionOrTimeout, type, options, args);
    }

    public FileChooser waitForFileChooser(int timeout) {
        if (ValidateUtil.isEmpty(this.fileChooserInterceptors)) {
            Map<String, Object> params = new HashMap<>();
            params.put("enabled", true);
            this.client.send("Page.setInterceptFileChooserDialog", params, true);
        }
        if (timeout <= 0)
            timeout = this.timeoutSettings.timeout();

        //TODO 暂时不知道callback到底是何物
//        this.fileChooserInterceptors.add(callback);
//        return helper.waitWithTimeout(promise, 'waiting for file chooser', timeout).catch(error = > {
//            this._fileChooserInterceptors.delete(callback);

        return null;
    }

    /**
     * 要在浏览器实例上下文执行方法
     *
     * @param pageFunction 要在浏览器实例上下文执行的方法
     * @param type         执行的类型，string or function
     * @param options      可选参数
     * @param args         执行的方法的参数
     * @return JSHandle
     */
    public JSHandle waitForFunction(String pageFunction, PageEvaluateType type, WaitForSelectorOptions options, Object... args) {
        return this.mainFrame().waitForFunction(pageFunction, type, options, args);
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空
     * <p>当url不为空时， type = PageEvaluateType.STRING </p>
     * <p>当predicate不为空时， type = PageEvaluateType.FUNCTION </p>
     *
     * @param url       等待的请求
     * @param predicate 方法
     * @param type      判别到底是url还是方法
     * @param timeout   超时时间
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Request waitForRequest(String url, Predicate<Request> predicate, PageEvaluateType type, int timeout) throws InterruptedException {
        if (timeout <= 0) {
            timeout = this.timeoutSettings.timeout();
        }
        Predicate<Request> predi = request -> {
            if (PageEvaluateType.STRING.equals(type)) {
                return url.equals(request.getUrl());
            } else if (PageEvaluateType.FUNCTION.equals(type)) {
                return predicate.test(request);
            }
            return false;
        };
        DefaultBrowserListener listener = null;
        try {
            listener = sessionClosePromise();
            return Helper.waitForEvent(this.frameManager.networkManager(), Events.NETWORK_MANAGER_REQUEST.getName(), predi, timeout, "Wait for request timeout");
        } finally {
            if (listener != null)
                this.client.removeListener(Events.CDPSESSION_DISCONNECTED.getName(), listener);
        }
    }

    private DefaultBrowserListener sessionClosePromise() {
        DefaultBrowserListener disConnectLis = new DefaultBrowserListener() {
            @Override
            public void onBrowserEvent(Object event) {
                throw new TerminateException("Target closed");
            }
        };
        disConnectLis.setMothod(Events.CDPSESSION_DISCONNECTED.getName());
        this.client.once(disConnectLis.getMothod(), disConnectLis);
        return disConnectLis;
    }

    /**
     * 等到某个请求，url或者predicate只有有一个不为空
     * <p>当url不为空时， type = PageEvaluateType.STRING </p>
     * <p>当predicate不为空时， type = PageEvaluateType.FUNCTION </p>
     *
     * @param url       等待的请求
     * @param predicate 方法
     * @param type      判别到底是url还是方法
     * @param timeout   超时时间
     * @return 要等到的请求
     * @throws InterruptedException 异常
     */
    public Response waitForResponse(String url, Predicate predicate, PageEvaluateType type, int timeout) throws InterruptedException {
        if (timeout <= 0)
            timeout = this.timeoutSettings.timeout();
        Predicate<Response> predi = response -> {
            if (PageEvaluateType.STRING.equals(type)) {
                return url.equals(response.getUrl());
            } else if (PageEvaluateType.FUNCTION.equals(type)) {
                return predicate.test(response);
            }
            return false;
        };
        DefaultBrowserListener listener = null;
        try {
            listener = sessionClosePromise();
            return Helper.waitForEvent(this.frameManager.networkManager(), Events.NETWORK_MANAGER_RESPONSE.getName(), predi, timeout, "Wait for response timeout");
        } finally {
            if (listener != null)
                this.client.removeListener(Events.CDPSESSION_DISCONNECTED.getName(), listener);
        }
    }

    /**
     * 等待指定的选择器匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的选择器在超时时间后扔不出现，此方法会报错。
     *
     * @param selector 要等待的元素选择器
     * @param options  可选参数
     * @return ElementHandle
     */
    public ElementHandle waitForSelector(String selector, WaitForSelectorOptions options) {
        return this.mainFrame().waitForSelector(selector, options);
    }

    /**
     * 等待指定的xpath匹配的元素出现在页面中，如果调用此方法时已经有匹配的元素，那么此方法立即返回。 如果指定的xpath在超时时间后扔不出现，此方法会报错。
     *
     * @param xpath   要等待的元素的xpath
     * @param options 可选参数
     * @return JSHandle
     */
    public JSHandle waitForXPath(String xpath, WaitForSelectorOptions options) {
        return this.mainFrame().waitForXPath(xpath, options);
    }

    /**
     * 该方法返回所有与页面关联的 WebWorkers
     *
     * @return WebWorkers
     */
    public java.util.Collection<Worker> workers() {
        return this.workers.values();
    }

    /**
     * 返回页面的地址
     *
     * @return 页面地址
     */
    private String url() {
        return this.mainFrame().url();
    }

    public CDPSession getClient() {
        return client;
    }

    public void setClient(CDPSession client) {
        this.client = client;
    }

    public Map<String, Worker> getWorkers() {
        return workers;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public Mouse getMouse() {
        return mouse;
    }

    public Target target() {
        return this.target;
    }

    public Touchscreen touchscreen() {
        return this.touchscreen;
    }

    public Tracing tracing() {
        return this.tracing;
    }

    public Accessibility accessibility() {
        return this.accessibility;
    }

    /**
     * 每个字符输入后都会触发 keydown, keypress/input 和 keyup 事件
     * <p>要点击特殊按键，比如 Control 或 ArrowDown，用 keyboard.press</p>
     *
     * @param selector 要输入内容的元素选择器。如果有多个匹配的元素，输入到第一个匹配的元素。
     * @param text     要输入的内容
     * @param delay    每个字符输入的延迟，单位是毫秒。默认是 0。
     * @throws InterruptedException
     */
    public void type(String selector, String text, int delay) throws InterruptedException {
        this.mainFrame().type(selector, text, delay);
    }

    public boolean getJavascriptEnabled() {
        return javascriptEnabled;
    }

    public Touchscreen getTouchscreen() {
        return touchscreen;
    }

    public Keyboard getKeyboard() {
        return this.keyboard;
    }

    /**
     * 获取Viewport
     * width <number> 宽度，单位是像素
     * height <number> 高度，单位是像素
     * deviceScaleFactor <number> 定义设备缩放， (类似于 dpr)。 默认 1。
     * isMobile <boolean> 要不要包含meta viewport 标签。 默认 false。
     * hasTouch<boolean> 指定终端是否支持触摸。 默认 false
     * isLandscape <boolean> 指定终端是不是 landscape 模式。 默认 false。
     *
     * @return Viewport
     */
    public Viewport viewport() {
        return this.viewport;
    }
}
