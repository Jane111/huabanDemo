package licola.demo.com.huabandemo.Service;

import android.app.Activity;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import licola.demo.com.huabandemo.API.OnProgressResponseListener;
import licola.demo.com.huabandemo.Base.HuaBanApplication;
import licola.demo.com.huabandemo.Entity.DownloadInfo;
import licola.demo.com.huabandemo.HttpUtils.RetrofitService;
import licola.demo.com.huabandemo.Util.FileUtils;
import licola.demo.com.huabandemo.Util.Logger;
import licola.demo.com.huabandemo.Util.NotificationUtils;
import licola.demo.com.huabandemo.Util.Utils;
import okhttp3.ResponseBody;
import rx.Subscriber;
import rx.functions.Func1;

/**
 * Created by LiCola on  2016/05/13  22:02
 * DownloadService 后台下载服务模块
 * 具有两个线程
 * DownloadThread线性负责单线程下载
 * notifyThread线程负责轮询接收下载进度 发出通知 生命周期依赖下载进程
 */
public class DownloadService extends IntentService {
    private static final String TAG = "DownloadService";
    private static final String KEYURL = "KeyUrl";
    private static final String KEYTYPE = "KeyType";


    private static final int MSG_START = 0;
    private static final int MSG_LOADING = 1;
    private static final int MSG_COMPLETE = 2;

    private OnProgressResponseListener mListener;

    private long mDelayedTime = 1000;

    private Map<Integer, DownloadInfo> mMap = new HashMap<>();

    private DownloadInfo mDownloadInfo;

    private NotifyHandler mNotifyHandler;

    private final class NotifyHandler extends Handler {
        //下载操作不频繁 可以当做类变量 使用时候再创建
        private NotificationManager mNotificationManager;

        public NotifyHandler(Looper looper) {
            super(looper);
            mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        }

        @Override
        public void handleMessage(Message msg) {
            DownloadInfo downloadInfo = (DownloadInfo) msg.obj;
            int notifyId = downloadInfo.mWorkId;
            Notification notification;
            Logger.d("what=" + msg.what + " obj=" + downloadInfo.toString());
            switch (msg.what) {
                case MSG_START:
                    notification = NotificationUtils.showNotification(
                            getApplication(),
                            downloadInfo.fileName,
                            downloadInfo.mState
                    );
                    break;
                case MSG_LOADING:
                    notification = NotificationUtils.showProcessNotification
                            (getApplication(), downloadInfo.fileName, downloadInfo.mProcess);
                    break;
                case MSG_COMPLETE:
                    notification = NotificationUtils.showIntentNotification(
                            getApplication(),
                            downloadInfo.mFile,
                            downloadInfo.mMediaType,
                            downloadInfo.fileName,
                            downloadInfo.mState
                    );

                    break;
                default:
                    notification = NotificationUtils.showNotification(
                            getApplication(),
                            "错误",
                            "无法处理接受的消息"
                    );
                    break;
            }
            mNotificationManager.notify(notifyId, notification);
        }
    }

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            Logger.d(Thread.currentThread().toString() + " mProcess=" + mDownloadInfo.mProcess);
            Message message = mNotifyHandler.obtainMessage();
            message.what = MSG_LOADING;
            message.obj = mDownloadInfo;
            mNotifyHandler.sendMessage(message);
            mNotifyHandler.postDelayed(this, mDelayedTime);
        }
    };

    public DownloadService() {
        super(TAG);
        Logger.d(TAG);
    }

    public static void launch(Activity activity, String url, String type) {
        Intent intent = new Intent(activity, DownloadService.class);
        intent.putExtra(KEYURL, url);
        intent.putExtra(KEYTYPE, type);
        activity.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.d(TAG);

        //线程不阻塞的回调 构造down对象 放入map中
        String url = intent.getStringExtra(KEYURL);
        String type = intent.getStringExtra(KEYTYPE);
        String filename = String.valueOf(System.currentTimeMillis()) + Utils.getPinsType(type);
        int mWorkId = url.hashCode();
        //根据参数 构造对象
        DownloadInfo mDownloadInfo = new DownloadInfo(filename, type, mWorkId, url, DownloadInfo.StateStart);
        Logger.d(mWorkId + " " + Thread.currentThread().toString());
        //根据url的hashcode 放入
        mMap.put(mWorkId, mDownloadInfo);
        sendNotifyMessage(mDownloadInfo);
        return super.onStartCommand(intent, flags, startId);
    }

    private void sendNotifyMessage(DownloadInfo mDownloadInfo) {
        Message message = mNotifyHandler.obtainMessage();
        message.what = MSG_START;
        message.obj = mDownloadInfo;
        mNotifyHandler.sendMessage(message);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        //处理队列中的消息 顺序调用 处理完一个再处理下一个
        //这里是线程阻塞方法 刚好可以好判断当前任务
        //从map中取出构造的好的 对象 开始任务
        String url = intent.getStringExtra(KEYURL);
        Logger.d(url.hashCode() + " " + Thread.currentThread().toString());
        mDownloadInfo = mMap.get(url.hashCode());
        actionDownload(url, mDownloadInfo);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.d(TAG);
        //获取通知管理器
        mListener = new OnProgressResponseListener() {
            @Override
            public void onResponseProgress(long bytesRead, long contentLength, boolean done) {
                //监听器 在下载线程回调 接收下载的具体进度
//                Logger.d(Thread.currentThread().toString() + " bytesRead=" + bytesRead + " contentLength=" + contentLength + " done=" + done);
                mDownloadInfo.mProcess = (int) (bytesRead * 100 / contentLength);
            }
        };

        HandlerThread thread = new HandlerThread("notifyThread");
        thread.start();
        mNotifyHandler = new NotifyHandler(thread.getLooper());
    }

    /**
     * 阻塞当前线程的下载方法
     *
     * @param mImageUrl
     */
    private void actionDownload(String mImageUrl, DownloadInfo mDownloadInfo) {

        RetrofitService.createDownloadService(mListener)
                .httpDownImage(mImageUrl)
                //IntentService 有内部变量HandlerThread 运行在子线程中 所以不用切换线程
//                .subscribeOn(Schedulers.io())
//                .observeOn(Schedulers.io())
                .map(new Func1<ResponseBody, File>() {
                    @Override
                    public File call(ResponseBody responseBody) {
                        Logger.d(responseBody.contentLength() + " " + responseBody.contentType().toString());
//                        mDownloadInfo.mMediaType = responseBody.contentType().toString();
                        File file = FileUtils.getDirsFile();//构造目录文件
                        Logger.d(file.getPath());

                        return FileUtils.writeResponseBodyToDisk(file, responseBody, mDownloadInfo.fileName);
                    }
                })
                .filter(file -> file != null)
                .subscribe(new Subscriber<File>() {
                    @Override
                    public void onStart() {
                        super.onStart();
                        //开始下载 线程开始轮询
                        mNotifyHandler.postDelayed(mRunnable, mDelayedTime);
                    }

                    @Override
                    public void onCompleted() {
                        Logger.d();
                    }

                    @Override
                    public void onError(Throwable e) {
                        Logger.d(e.toString());
                    }

                    @Override
                    public void onNext(File file) {
                        Logger.d(file.getAbsolutePath());
                        mNotifyHandler.removeCallbacks(mRunnable);

                        sendFileNotifyMessage(file, mDownloadInfo);
                    }
                });
    }

    private void sendFileNotifyMessage(File file, DownloadInfo mDownloadInfo) {
        mDownloadInfo.mFile = file;
        mDownloadInfo.mState = "下载完成";
        Message message = mNotifyHandler.obtainMessage();
        message.what = MSG_COMPLETE;
        message.obj = mDownloadInfo;
        mNotifyHandler.sendMessage(message);
    }

    @Override
    public void onStart(Intent intent, int startId) {
        Logger.d(TAG);
        super.onStart(intent, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.d(TAG + " " + Thread.currentThread().toString());
        mListener = null;
        mDownloadInfo=null;

        HuaBanApplication.getRefwatcher(this).watch(this);

    }

}
