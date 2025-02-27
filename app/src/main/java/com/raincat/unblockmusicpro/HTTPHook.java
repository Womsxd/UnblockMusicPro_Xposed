package com.raincat.unblockmusicpro;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.widget.Toast;

import com.stericson.RootShell.execution.Command;

import net.androidwing.hotxposed.IHookerDispatcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;

import javax.net.ssl.SSLSocketFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2019/09/07
 *     desc   : Hook
 *     version: 1.0
 * </pre>
 */

public class HTTPHook implements IHookerDispatcher {
    private static int versionCode = 0;
    private static String codePath = "";
    private static boolean firstToastShow = true;
    private static boolean hookStart = false;
    private static boolean showLog = false;

    private static Object sslSocketFactory = null;
    private static String sslSocketFactoryFieldString = null;
    private static SharedPreferences preferences;

    @Override
    public void dispatch(XC_LoadPackage.LoadPackageParam lpparam) {
        //Hook入口
        if (lpparam.packageName.equals(Tools.HOOK_NAME)) {
            findAndHookMethod(findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Context neteaseContext = (Context) param.thisObject;
                            if (!Setting.getEnable()) {
                                Command stop = new Command(0, Tools.Stop);
                                Tools.shell(neteaseContext, stop);
                                return;
                            }

                            try {
                                PackageInfo info = neteaseContext.getPackageManager().getPackageInfo(Tools.HOOK_NAME, 0);
                                versionCode = info.versionCode;
                            } catch (NullPointerException e) {
                                e.printStackTrace();
                            }

                            preferences = neteaseContext.getSharedPreferences("share", Context.MODE_MULTI_PROCESS);
                            preferences.edit().remove("hook").apply();

                            final String processName = Tools.getCurrentProcessName(neteaseContext);
                            
                            //主进程脚本注入
                            Boolean onlyUseProxy = !Setting.DEFAULT_PROXY.equals(Setting.getProxy());
                            if (!onlyUseProxy && processName.equals(Tools.HOOK_NAME)) {
                                if (!initData(neteaseContext))
                                    return;
                            } else if (processName.equals(Tools.HOOK_NAME + ":play")) {
                                if (onlyUseProxy || initData(neteaseContext)) {
                                    if (onlyUseProxy) {
                                        new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                Tools.showToastOnLooper(neteaseContext, "运行成功，使用代理：" + Setting.getProxy());
                                            }
                                        }).start();
                                        return;
                                    }
                                    showLog = Setting.getLog();
                                    String port = " -p 23338:23339";
                                    Command start = new Command(0, Tools.Stop, "cd " + codePath, Setting.getNodejs() + port) {
                                        @Override
                                        public void commandOutput(int id, String line) {
                                            if (showLog)
                                                XposedBridge.log(line);
                                            if (firstToastShow) {
                                                if (line.contains("Error")) {
                                                    Tools.showToastOnLooper(neteaseContext, "运行失败，错误为：" + line);
                                                    firstToastShow = false;
                                                } else if (line.contains("HTTP Server running")) {
                                                    if (preferences == null)
                                                        preferences = neteaseContext.getSharedPreferences("share", Context.MODE_MULTI_PROCESS);
                                                    preferences.edit().putBoolean("hook", true).apply();
                                                    Tools.showToastOnLooper(neteaseContext, "运行成功，当前优先选择" + Setting.getOriginString() + "音源");
                                                    firstToastShow = false;
                                                }
                                            }
                                        }
                                    };
                                    Tools.shell(neteaseContext, start);
                                } else {
                                    Toast.makeText(neteaseContext, "文件完整性校验失败，请打开UnblockMusic Pro并同意存储卡访问权限!", Toast.LENGTH_LONG).show();
                                    return;
                                }
                            }

                            if (processName.equals(Tools.HOOK_NAME) || processName.equals(Tools.HOOK_NAME + ":play")) {
                                final SSLSocketFactory socketFactory = Tools.getSLLContext(codePath + File.separator + "ca.crt").getSocketFactory();                               
                                String[] ipPort = Setting.getProxy().split(":");
                                final Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(ipPort[0], Integer.valueOf(ipPort[1])));
                                if (versionCode == 110) {
                                    //强制HTTP走本地代理
                                    hookAllConstructors(findClass("okhttp3.a", neteaseContext.getClassLoader()), new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam param) {
                                            if (param.args.length >= 9) {
                                                param.args[8] = proxy;
//                                                if (Setting.getSSL())
//                                                    param.args[4] = socketFactory;
                                            }
                                        }
                                    });
                                } else if (versionCode >= 138) {
                                    String realCallClassString = "okhttp3.RealCall";
                                    sslSocketFactoryFieldString = "sslSocketFactory";
                                    if (versionCode >= 7001080) {
                                        realCallClassString = "okhttp3.internal.connection.RealCall";
                                        sslSocketFactoryFieldString = "sslSocketFactoryOrNull";
                                    }
                                    //强制走代理模式
                                    hookAllConstructors(findClass(realCallClassString, neteaseContext.getClassLoader()), new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                            if (!hookStart) {
                                                preferences = neteaseContext.getSharedPreferences("share", Context.MODE_MULTI_PROCESS);
                                                hookStart = preferences.getBoolean("hook", false);
                                            }
                                            if (!hookStart)
                                                return;

                                            if (param.args.length == 3) {
                                                Object client = param.args[0];
                                                Object request = param.args[1];

                                                Field urlField = request.getClass().getDeclaredField("url");
                                                urlField.setAccessible(true);
                                                Field proxyField = client.getClass().getDeclaredField("proxy");
                                                proxyField.setAccessible(true);
                                                Field sslSocketFactoryField = client.getClass().getDeclaredField(sslSocketFactoryFieldString);
                                                sslSocketFactoryField.setAccessible(true);
                                                if (sslSocketFactory == null) {
                                                    sslSocketFactory = sslSocketFactoryField.get(client);
                                                }

                                                Object urlObj = urlField.get(request);
                                                if (urlObj.toString().contains("yyaac") || urlObj.toString().contains("eapi/cloud") || urlObj.toString().contains("ymusic") || urlObj.toString().contains("&thumbnail")) {
                                                    proxyField.set(client, null);
                                                    sslSocketFactoryField.set(client, sslSocketFactory);
                                                } else {
                                                    proxyField.set(client, proxy);
                                                    sslSocketFactoryField.set(client, socketFactory);
                                                }
                                                param.args[0] = client;
                                            }
                                        }
                                    });
                                }
                            }
                        }
                    });
        }
    }

    private static void logText(String name, Object object) {
        Field[] fields = object.getClass().getDeclaredFields();
        for (Field field : fields) {
            try {
                boolean accessFlag1 = field.isAccessible();
                field.setAccessible(true);
                XposedBridge.log(name + "->" + field.getName() + "->" + field.get(object));
                field.setAccessible(accessFlag1);
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            } catch (IllegalAccessException ex) {
                ex.printStackTrace();
            }
        }
    }

    //释放脚本文件
    private boolean initData(Context context) {
        codePath = context.getFilesDir().getAbsolutePath();
        //比对版本
        String sdCartVersionString = Tools.readFileFromSD(Tools.SDCardPath + File.separator + "package.json");
        String codeVersionString = Tools.readFileFromSD(codePath + File.separator + "package.json");
        int nowVersion = Integer.parseInt(Tools.nowVersion.replace(".", "00").replace("-high", ""));
        int sdCartVersion = 0;
        try {
            //对比SD卡与安装包的脚本版本，不对则返回错误
            if (sdCartVersionString.length() != 0) {
                JSONObject jsonObject = new JSONObject(sdCartVersionString);
                sdCartVersionString = jsonObject.getString("version");
                sdCartVersion = Integer.parseInt(sdCartVersionString.replace(".", "00").replace("-high", ""));
            }
            if (sdCartVersion < nowVersion) {
                return false;
            }

            //对比SD卡与网易云内部脚本版本，不对则拷贝SD卡脚本文件到网易云内部
            if (codeVersionString.length() != 0) {
                JSONObject jsonObject = new JSONObject(codeVersionString);
                codeVersionString = jsonObject.getString("version");
            }
            if (!codeVersionString.equals(sdCartVersionString)) {
                Tools.copyFilesFromSD(Tools.SDCardPath, codePath);
                Command command = new Command(0, "cd " + codePath, "chmod 770 *");
                Tools.shell(context, command);
            }
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }

        codeVersionString = Tools.readFileFromSD(codePath + File.separator + "package.json");
        return codeVersionString.length() != 0;
    }
}