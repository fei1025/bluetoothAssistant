package com.zzf.bluetoothsmp;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Framework-only UI exercise against the real minified Release; no keeps added for testing. */
@RunWith(AndroidJUnit4.class)
public class OptimizedPluginSmokeTest {
    @Test public void reviewedExampleInstallsAndRunsInStrictOptimizedHost() throws Exception {
        Instrumentation test=InstrumentationRegistry.getInstrumentation();
        Application app=(Application)test.getTargetContext().getApplicationContext();
        List<Activity> created=new ArrayList<>();AtomicReference<Activity> current=new AtomicReference<>();
        Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            @Override public void onActivityCreated(Activity a,Bundle b){created.add(a);}
            @Override public void onActivityResumed(Activity a){current.set(a);}
            @Override public void onActivityStarted(Activity a){}
            @Override public void onActivityPaused(Activity a){}
            @Override public void onActivityStopped(Activity a){}
            @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
            @Override public void onActivityDestroyed(Activity a){}
        };
        test.runOnMainSync(()->app.registerActivityLifecycleCallbacks(lifecycle));
        try{
            Activity center=test.startActivitySync(new Intent().setClassName(app.getPackageName(),app.getPackageName()+".plugin.PluginCenterActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(test,()->findText(center.getWindow().getDecorView(),app.getString(string(app,"plugin_example_classic")))!=null);
            AtomicBoolean developer=new AtomicBoolean();test.runOnMainSync(()->{int id=app.getResources().getIdentifier("plugin_developer_mode","string",app.getPackageName());developer.set(id!=0&&findText(center.getWindow().getDecorView(),app.getString(id))!=null);});
            assertFalse("Release must never expose developer mode",developer.get());
            test.runOnMainSync(()->findText(center.getWindow().getDecorView(),app.getString(string(app,"plugin_example_classic"))).performClick());
            clickConfirmation(test,app.getString(string(app,"confirm")));
            await(test,()->findText(center.getWindow().getDecorView(),app.getString(string(app,"plugin_operation_ok")))!=null);
            test.runOnMainSync(()->findText(center.getWindow().getDecorView(),app.getString(string(app,"plugin_run"))).performClick());
            await(test,()->current.get()!=null&&current.get()!=center&&findText(current.get().getWindow().getDecorView(),app.getString(string(app,"plugin_stop")))!=null);
            Activity runner=current.get();AtomicReference<WebView> web=new AtomicReference<>();
            await(test,()->{web.set(findWeb(runner.getWindow().getDecorView()));return web.get()!=null||findText(runner.getWindow().getDecorView(),app.getString(string(app,"plugin_webview_update")))!=null;});
            if(web.get()!=null){
                awaitJs(test,web.get(),"Boolean(window.btAssistant)");
                js(test,web.get(),"btAssistant.ready().then(h=>{window.__strictRelease=h.developerMode===false;return btAssistant.storage.set('releaseSmoke',42)}).then(()=>btAssistant.storage.get('releaseSmoke')).then(v=>window.__releaseValue=v)");
                awaitJs(test,web.get(),"window.__strictRelease===true && window.__releaseValue===42");
                js(test,web.get(),"fetch('https://example.com/').then(()=>window.__releaseNetwork='allowed').catch(()=>window.__releaseNetwork='blocked')");
                awaitJs(test,web.get(),"window.__releaseNetwork==='blocked'");
            }
            test.runOnMainSync(()->findText(runner.getWindow().getDecorView(),app.getString(string(app,"plugin_stop"))).performClick());
        }finally{
            test.runOnMainSync(()->{for(Activity a:created)if(!a.isDestroyed())a.finishAndRemoveTask();app.unregisterActivityLifecycleCallbacks(lifecycle);});
            test.waitForIdleSync();
        }
    }
    private static int string(Context c,String key){int id=c.getResources().getIdentifier(key,"string",c.getPackageName());assertNotEquals(0,id);return id;}
    private static View findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static WebView findWeb(View v){if(v instanceof WebView)return (WebView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private interface Check{boolean ready();}
    private static void clickConfirmation(Instrumentation test,String text){
        long deadline=SystemClock.uptimeMillis()+10000;
        do{
            android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if(root!=null){for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
                if(node.isClickable()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return;
            }}
            SystemClock.sleep(50);
        }while(SystemClock.uptimeMillis()<deadline);fail("Native installation confirmation missing");
    }
    private static void await(Instrumentation test,Check check){long deadline=SystemClock.uptimeMillis()+15000;AtomicBoolean ready=new AtomicBoolean();do{test.runOnMainSync(()->ready.set(check.ready()));if(ready.get())return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);fail("Optimized plugin UI did not settle");}
    private static String js(Instrumentation test,WebView web,String code)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();test.runOnMainSync(()->web.evaluateJavascript(code,v->{value.set(v);done.countDown();}));assertTrue(done.await(5,TimeUnit.SECONDS));return value.get();}
    private static void awaitJs(Instrumentation test,WebView web,String code)throws Exception{long deadline=SystemClock.uptimeMillis()+15000;do{if("true".equals(js(test,web,code)))return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);fail("Optimized SDK did not settle: "+code);}
}
