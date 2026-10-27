package com.zzf.bluetoothsmp.plugin;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.webkit.WebViewFeature;
import com.zzf.bluetoothsmp.R;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PluginPlatformInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;
    private PluginStore store;
    private PluginStore.Installed installed;
    private PluginActivity activity;
    @Before public void before() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();store=new PluginStore(context);
        File archive=example("classic");try{installed=store.install(archive,null);}finally{archive.delete();}
        store.clearData(installed);
    }
    @After public void after() throws Exception {
        if(activity!=null){main(activity::finishAndRemoveTask);instrumentation.waitForIdleSync();}
        if(installed!=null&&!PluginRuntime.isRunning(installed.manifest.id)){store.clearData(installed);store.uninstall(installed);}
    }
    private File example(String name) throws Exception {
        File target=File.createTempFile("plugin-test-",".btplugin",context.getCacheDir());
        try(InputStream in=context.getAssets().open("plugins/examples/"+name+".btplugin");OutputStream out=new FileOutputStream(target)){out.write(PluginStore.read(in,PluginArchive.MAX_ARCHIVE));}
        return target;
    }
    @Test public void bundledExamplesPassStrictReleaseVerification() throws Exception {
        for(String name:Arrays.asList("classic","ble")){
            File archive=example(name);File staging=new File(context.getCacheDir(),"verify-"+UUID.randomUUID());
            try{
                PluginArchive verified=new PluginArchive(archive,staging,store.reviewKeys(),false,value->android.util.Base64.decode(value,android.util.Base64.DEFAULT));
                assertTrue(verified.reviewed);assertEquals("com.zzf.example."+name,verified.manifest.id);
            }finally{archive.delete();remove(staging);}
        }
    }
    @Test public void uninstallAndReinstallPreserveConfigurationAndClearAuthorization() throws Exception {
        store.store(installed,"probe",new JSONObject().put("value",42),false);
        assertTrue(store.grant(installed,installed.manifest.capabilities));
        assertTrue(store.grantDevice(installed,"classic","02:00:00:00:FA:01"));
        store.uninstall(installed);assertTrue(store.grants(installed).isEmpty());
        File archive=example("classic");try{installed=store.install(archive,null);}finally{archive.delete();}
        assertEquals(42,store.storage(installed).getJSONObject("probe").getInt("value"));
        assertFalse(store.deviceGranted(installed,"classic","02:00:00:00:FA:01"));
    }
    @Test public void failedInstallKeepsActiveVersionAndConfiguration() throws Exception {
        store.store(installed,"keep","original",false);String digest=installed.digest;
        File invalid=File.createTempFile("invalid-",".btplugin",context.getCacheDir());
        try{
            try(OutputStream out=new FileOutputStream(invalid)){out.write(new byte[]{1,2,3,4});}
            try{store.install(invalid,null);fail();}catch(PluginException expected){}
            assertEquals(digest,store.load(installed.manifest.id).digest);assertEquals("original",store.storage(installed).getString("keep"));
        }finally{invalid.delete();}
    }
    @Test public void updatesRetainConfigRequireNewCapabilitiesAndPreserveAuthorAfterUninstall() throws Exception {
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.KeyPair author=generator.generateKeyPair(),other=generator.generateKeyPair();
        String id="test.continuity.t"+UUID.randomUUID().toString().replace("-","");PluginStore.Installed current=null;
        Set<String> first=new HashSet<>(Collections.singleton("ble.scan")),second=new HashSet<>(Arrays.asList("ble.scan","ble.connect"));
        List<File> packages=new ArrayList<>();
        try{
            File v1=authorPackage(id,1,author,first,"one");packages.add(v1);current=store.install(v1,null);store.store(current,"keep",42,false);store.grant(current,first);
            File v2=authorPackage(id,2,author,second,"two");packages.add(v2);current=store.install(v2,null);
            assertEquals(42,store.storage(current).getInt("keep"));assertEquals(first,store.grants(current));assertFalse(store.grants(current).contains("ble.connect"));
            try{store.install(v1,null);fail();}catch(PluginException e){assertEquals("VERSION_DOWNGRADE",e.code);}
            File changed=authorPackage(id,2,author,second,"changed");packages.add(changed);
            try{store.install(changed,null);fail();}catch(PluginException e){assertEquals("VERSION_CONFLICT",e.code);}
            store.uninstall(current);File impersonator=authorPackage(id,3,other,second,"other");packages.add(impersonator);
            try{store.install(impersonator,null);fail();}catch(PluginException e){assertEquals("AUTHOR_CHANGED",e.code);}
            current=store.install(v2,null);assertEquals(42,store.storage(current).getInt("keep"));
        }finally{if(current!=null){store.clearData(current);store.uninstall(current);}for(File file:packages)file.delete();}
    }
    private File authorPackage(String id,int version,java.security.KeyPair key,Set<String> caps,String text)throws Exception{
        byte[] html=("<!doctype html><h1>"+text+"</h1>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] metadata=new JSONObject().put("id",id).put("name","Continuity test").put("author","Test").put("version",version).put("sdk",1).put("entry","index.html")
                .put("capabilities",new JSONArray(caps)).put("authorKey",android.util.Base64.encodeToString(key.getPublic().getEncoded(),android.util.Base64.NO_WRAP))
                .put("files",new JSONObject().put("index.html",PluginSignatures.sha256(html))).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withECDSA");signer.initSign(key.getPrivate());signer.update(metadata);
        byte[] signature=new JSONObject().put("author",android.util.Base64.encodeToString(signer.sign(),android.util.Base64.NO_WRAP)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File file=File.createTempFile("continuity-",".btplugin",context.getCacheDir());
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(new FileOutputStream(file))){
            for(Object[] resource:new Object[][]{{"plugin.json",metadata},{"signature.json",signature},{"index.html",html}}){zip.putNextEntry(new java.util.zip.ZipEntry((String)resource[0]));zip.write((byte[])resource[1]);zip.closeEntry();}
        }return file;
    }
    @Test public void originAndFrameChecksRejectUntrustedCallers() {
        String origin="https://p-test.plugins.invalid";
        assertTrue(PluginWebView.allowedOrigin(android.net.Uri.parse(origin),origin,true));
        assertFalse(PluginWebView.allowedOrigin(android.net.Uri.parse(origin),origin,false));
        assertFalse(PluginWebView.allowedOrigin(android.net.Uri.parse("https://evil.invalid"),origin,true));
        assertFalse(PluginWebView.allowedOrigin(android.net.Uri.parse(origin+":444"),origin,true));
    }
    @Test public void runtimeLoadsOfflineOrExplainsUnsupportedSystemWebView() throws Exception {
        open();
        if(!supported()){
            await(()->activity.findViewById(R.id.plugin_webview)==null&&((android.widget.TextView)activity.findViewById(R.id.plugin_runtime_status))
                    .getText().toString().contains(activity.getString(R.string.plugin_webview_update)));
            return;
        }
        awaitJs("Boolean(window.btAssistant && document.getElementById('status') && document.getElementById('status').textContent.includes('Ready'))");
        js("window.btAssistant.storage.set('probe',{value:42}).then(()=>window.btAssistant.storage.get('probe')).then(v=>window.__probe=v.value)");
        awaitJs("window.__probe===42");
        assertEquals(42,store.storage(installed).getJSONObject("probe").getInt("value"));
    }
    @Test public void safeSdkRejectsUndeclaredCapabilitiesAndNetworkAccess() throws Exception {
        org.junit.Assume.assumeTrue("Requires modern system WebView",supported());open();awaitJs("Boolean(window.btAssistant)");
        js("window.btAssistant.ble.scan().catch(e=>window.__denied=e.code)");awaitJs("window.__denied==='CAPABILITY_NOT_DECLARED'");
        js("fetch('https://example.com/').then(()=>window.__network='allowed').catch(()=>window.__network='blocked')");awaitJs("window.__network==='blocked'");
        assertEquals("true",js("typeof WebSocket==='undefined' && typeof RTCPeerConnection==='undefined' && typeof Worker==='undefined' && typeof navigator.bluetooth==='undefined'"));
        assertTrue(store.grants(installed).isEmpty());
    }
    @Test public void rotationRetainsPageContextAndRunningPluginCannotBeReplaced() throws Exception {
        org.junit.Assume.assumeTrue("Requires modern system WebView",supported());open();awaitJs("Boolean(window.btAssistant)");js("window.__rotationProbe='retained'");
        File archive=example("classic");try{try{store.install(archive,null);fail();}catch(PluginException error){assertEquals("PLUGIN_RUNNING",error.code);}}finally{archive.delete();}
        PluginActivity old=activity;main(old::recreate);
        await(()->{
            for(Activity current:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(current instanceof PluginActivity&&current!=old){activity=(PluginActivity)current;return current.findViewById(R.id.plugin_webview)!=null;}
            return false;
        });
        awaitJs("window.__rotationProbe==='retained'");
    }
    @Test public void knownRevocationStopsLaunchWithoutDeletingConfiguration() throws Exception {
        store.store(installed,"keep","retained",false);
        android.content.SharedPreferences preferences=context.getSharedPreferences("plugin_catalog",Context.MODE_PRIVATE);
        Set<String> previous=new HashSet<>(preferences.getStringSet("revoked",Collections.emptySet()));
        try{
            Set<String> revoked=new HashSet<>(previous);revoked.add(installed.digest);assertTrue(preferences.edit().putStringSet("revoked",revoked).commit());
            try{store.load(installed.manifest.id);fail();}catch(PluginException error){assertEquals("VERSION_REVOKED",error.code);}
            assertEquals("retained",store.storage(installed).getString("keep"));
            boolean visible=false; for(PluginStore.Installed item:store.list()) if(item.manifest.id.equals(installed.manifest.id))visible=true; assertTrue(visible);
        }finally{preferences.edit().putStringSet("revoked",previous).commit();}
    }
    @Test public void storageIsIsolatedAndQuotaFailurePreservesExistingConfiguration() throws Exception {
        File archive=example("ble"); PluginStore.Installed other;
        try{other=store.install(archive,null);}finally{archive.delete();}
        try{
            store.store(installed,"key","classic",false);store.store(other,"key","ble",false);
            assertEquals("classic",store.storage(installed).getString("key"));assertEquals("ble",store.storage(other).getString("key"));
            char[] oversized=new char[1024*1024];Arrays.fill(oversized,'x');
            try{store.store(installed,"large",new String(oversized),false);fail();}catch(PluginException error){assertEquals("STORAGE_QUOTA",error.code);}
            assertEquals("classic",store.storage(installed).getString("key"));assertFalse(store.storage(installed).has("large"));
        }finally{store.clearData(other);store.uninstall(other);}
    }
    @Test public void atomicBackupsRecoverInstalledPointerAndConfiguration() throws Exception {
        store.store(installed,"recover","original",false);
        File root=new File(context.getNoBackupFilesDir(),"plugins");
        File data=new File(root,"data/"+installed.identity()+".json"),pointer=new File(root,installed.manifest.id+".active");
        assertTrue(data.renameTo(new File(data.getPath()+".bak")));
        assertTrue(pointer.renameTo(new File(pointer.getPath()+".bak")));
        assertEquals("original",store.storage(installed).getString("recover"));
        boolean recovered=false;for(PluginStore.Installed item:store.list())if(item.digest.equals(installed.digest))recovered=true;
        assertTrue(recovered);assertTrue(data.exists());assertTrue(pointer.exists());
    }
    @Test public void catalogueRollbackAndWithdrawalRemovalCannotReenableKnownBadContent() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("plugin_catalog",Context.MODE_PRIVATE);
        int previousRevision=prefs.getInt("revision",0);String previousHash=prefs.getString("hash","");
        Set<String> previous=new HashSet<>(prefs.getStringSet("revoked",Collections.emptySet()));
        java.lang.reflect.Method apply=PluginCatalog.class.getDeclaredMethod("apply",JSONObject.class);apply.setAccessible(true);PluginCatalog catalogue=new PluginCatalog(context);
        try{
            int revision=previousRevision+1;
            apply.invoke(catalogue,new JSONObject().put("revision",revision).put("plugins",new JSONArray()).put("revoked",new JSONArray().put(installed.digest)));
            apply.invoke(catalogue,new JSONObject().put("revision",revision+1).put("plugins",new JSONArray()).put("revoked",new JSONArray()));
            assertTrue(store.isRevoked(installed.digest));
            try{apply.invoke(catalogue,new JSONObject().put("revision",revision).put("plugins",new JSONArray()).put("revoked",new JSONArray()));fail();}
            catch(java.lang.reflect.InvocationTargetException error){assertEquals("CATALOG_ROLLBACK",((PluginException)error.getCause()).code);}
        }finally{prefs.edit().putInt("revision",previousRevision).putString("hash",previousHash).putStringSet("revoked",previous).commit();}
    }
    @Test public void lateConsentCannotRestoreRevokedAuthorization() throws Exception {
        org.junit.Assume.assumeTrue("Requires modern system WebView",supported());open();awaitJs("Boolean(window.btAssistant)");
        AtomicReference<PluginRuntime.Consent> consent=new AtomicReference<>();AtomicReference<PluginRuntime> runtime=new AtomicReference<>();
        main(()->{runtime.set(new ViewModelProvider(activity).get(PluginRuntime.class));runtime.get().attach(new PluginRuntime.Ui(){
            @Override public void capabilities(PluginStore.Installed p,Set<String> caps,PluginRuntime.Consent reply){consent.set(reply);}
            @Override public void device(PluginStore.Installed p,String transport,String address,PluginRuntime.Consent reply){fail("Unexpected device prompt");}
            @Override public void status(String value){}
        },activity);});
        js("btAssistant.classic.paired().catch(e=>window.__revokeError=e.code)");await(()->consent.get()!=null);
        main(()->{runtime.get().revoke();consent.get().result(true);});
        awaitJs("window.__revokeError==='PERMISSION_DENIED'");assertTrue(store.grants(installed).isEmpty());
        assertEquals("true",js("typeof btAssistant.storage.get==='function'"));
    }
    @Test public void pageReloadEstablishesFreshContextAndDoesNotLeaveRuntimePaused() throws Exception {
        org.junit.Assume.assumeTrue("Requires modern system WebView",supported());open();awaitJs("Boolean(window.btAssistant)");
        js("window.__oldPage='old'");main(()->((WebView)activity.findViewById(R.id.plugin_webview)).reload());
        awaitJs("typeof window.__oldPage==='undefined' && Boolean(window.btAssistant && document.getElementById('status') && document.getElementById('status').textContent.includes('Ready'))");
        js("btAssistant.storage.set('reload',true).then(()=>window.__reloadReady=true)");awaitJs("window.__reloadReady===true");
    }
    @Test public void invalidSdkPayloadRejectsImmediatelyWithStableErrorWithoutQueueLeak() throws Exception {
        org.junit.Assume.assumeTrue("Requires modern system WebView",supported());open();awaitJs("Boolean(window.btAssistant)");
        js("btAssistant.classic.write({bytes:[1]}).catch(e=>window.__invalid=e.code+':'+Boolean(e.requestId))");awaitJs("window.__invalid==='INVALID_ARGUMENT:true'");
        js("const cycle={};cycle.self=cycle;btAssistant.storage.set('cycle',cycle).catch(e=>window.__cycle=e.code)");awaitJs("window.__cycle==='INVALID_ARGUMENT'");
        js("btAssistant.host.getState().then(()=>window.__stillWorks=true)");awaitJs("window.__stillWorks===true");
    }
    private void open(){activity=(PluginActivity)instrumentation.startActivitySync(new Intent(context,PluginActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("pluginId",installed.manifest.id));instrumentation.waitForIdleSync();}
    private boolean supported(){return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)&&WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);}
    private void main(Runnable action){instrumentation.runOnMainSync(action);}
    private String js(String source) throws Exception {
        AtomicReference<String> value=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
        main(()->{WebView view=activity.findViewById(R.id.plugin_webview);assertNotNull(view);view.evaluateJavascript(source,result->{value.set(result);done.countDown();});});
        assertTrue(done.await(5,TimeUnit.SECONDS));return value.get();
    }
    private void awaitJs(String condition) throws Exception {
        long deadline=SystemClock.uptimeMillis()+10000;
        do{if("true".equals(js(condition)))return;SystemClock.sleep(50);}while(SystemClock.uptimeMillis()<deadline);
        fail("JavaScript condition did not settle: "+condition);
    }
    private interface Check{boolean ready();}
    private void await(Check check){long deadline=SystemClock.uptimeMillis()+10000;AtomicBoolean ready=new AtomicBoolean();
        do{main(()->ready.set(check.ready()));if(ready.get())return;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<deadline);fail("UI did not settle");}
    private void remove(File file)throws Exception{
        if(!file.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath()+File.separator))throw new IOException();
        File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();
    }
}
