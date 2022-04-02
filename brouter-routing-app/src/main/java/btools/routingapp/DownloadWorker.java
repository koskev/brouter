package btools.routingapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import btools.mapaccess.PhysicalFile;
import btools.mapaccess.Rd5DiffManager;
import btools.mapaccess.Rd5DiffTool;
import btools.util.ProgressListener;

public class DownloadWorker extends Worker {
  public static final String KEY_INPUT_SEGMENT_NAMES = "SEGMENT_NAMES";
  public static final String PROGRESS_SEGMENT_NAME = "PROGRESS_SEGMENT_NAME";
  public static final String PROGRESS_SEGMENT_PERCENT = "PROGRESS_SEGMENT_PERCENT";

  private static final int NOTIFICATION_ID = 1;
  private static final String PROFILES_DIR = "profiles2/";
  private static final String SEGMENTS_DIR = "segments4/";
  private static final String SEGMENT_DIFF_SUFFIX = ".df5";
  private static final String SEGMENT_SUFFIX = ".rd5";

  private NotificationManager notificationManager;
  private ServerConfig mServerConfig;
  private File baseDir;
  private ProgressListener diffProgressListener;
  private DownloadProgressListener downloadProgressListener;
  private Data.Builder progressBuilder = new Data.Builder();
  private NotificationCompat.Builder notificationBuilder;

  public DownloadWorker(
    @NonNull Context context,
    @NonNull WorkerParameters parameters) {
    super(context, parameters);
    notificationManager = (NotificationManager)
      context.getSystemService(Context.NOTIFICATION_SERVICE);
    mServerConfig = new ServerConfig(context);
    baseDir = new File(ConfigHelper.getBaseDir(context), "brouter");

    notificationBuilder = createNotificationBuilder();

    diffProgressListener = new ProgressListener() {
      @Override
      public void updateProgress(String progress) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress));
      }

      @Override
      public boolean isCanceled() {
        return isStopped();
      }
    };

    downloadProgressListener = new DownloadProgressListener() {
      @Override
      public void updateProgress(int max, int progress) {
        if (max > 0) {
          notificationBuilder.setProgress(max, progress, false);
          notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
          progressBuilder.putInt(PROGRESS_SEGMENT_PERCENT, progress * 100 / max);
          setProgressAsync(progressBuilder.build());
        } else {
          notificationBuilder.setProgress(0, 0, true);
          notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
          progressBuilder.putInt(PROGRESS_SEGMENT_PERCENT, -1);
          setProgressAsync(progressBuilder.build());
        }
      }

      @Override
      public void updateProgress(String content) {
        notificationBuilder.setContentText(content);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
      }
    };

    progressBuilder.putInt(PROGRESS_SEGMENT_PERCENT, 0);
    setProgressAsync(progressBuilder.build());
  }

  @NonNull
  @Override
  public Result doWork() {
    Data inputData = getInputData();
    String[] segmentNames = inputData.getStringArray(KEY_INPUT_SEGMENT_NAMES);
    if (segmentNames == null) {
      return Result.failure();
    }
    // Mark the Worker as important
    setForegroundAsync(new ForegroundInfo(NOTIFICATION_ID, createNotification("Starting Download")));
    try {
      notificationManager.notify(NOTIFICATION_ID, createNotification("Updating profiles"));
      downloadLookupAndProfiles();

      int segmentIndex = 1;
      for (String segmentName : segmentNames) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(String.format("%s (%d/%d)", segmentName, segmentIndex, segmentNames.length)));
        progressBuilder.putString(PROGRESS_SEGMENT_NAME, segmentName);
        setProgressAsync(progressBuilder.build());
        downloadSegment(mServerConfig.getSegmentUrl(), segmentName + SEGMENT_SUFFIX);
        segmentIndex++;
      }
    } catch (IOException e) {
      return Result.failure();
    } catch (InterruptedException e) {
      return Result.failure();
    }
    return Result.success();
  }

  private void downloadLookupAndProfiles() throws IOException, InterruptedException {
    String[] lookups = mServerConfig.getLookups();
    for (String fileName : lookups) {
      if (fileName.length() > 0) {
        File lookupFile = new File(baseDir, PROFILES_DIR + fileName);
        String lookupLocation = mServerConfig.getLookupUrl() + fileName;
        URL lookupUrl = new URL(lookupLocation);
        downloadFile(lookupUrl, lookupFile, false);
      }
    }

    String[] profiles = mServerConfig.getProfiles();
    for (String fileName : profiles) {
      if (fileName.length() > 0) {
        File profileFile = new File(baseDir, PROFILES_DIR + fileName);
        if (profileFile.exists()) {
          String profileLocation = mServerConfig.getProfilesUrl() + fileName;
          URL profileUrl = new URL(profileLocation);
          downloadFile(profileUrl, profileFile, false);
        }
      }
    }
  }

  private void downloadSegment(String segmentBaseUrl, String segmentName) throws IOException, InterruptedException {
    File segmentFile = new File(baseDir, SEGMENTS_DIR + segmentName);
    File segmentFileTemp = new File(segmentFile.getAbsolutePath() + "_tmp");
    try {
      if (segmentFile.exists()) {
        downloadProgressListener.updateProgress("Calculating local checksum...");
        String md5 = Rd5DiffManager.getMD5(segmentFile);
        String segmentDeltaLocation = segmentBaseUrl + "diff/" + segmentName.replace(SEGMENT_SUFFIX, "/" + md5 + SEGMENT_DIFF_SUFFIX);
        URL segmentDeltaUrl = new URL(segmentDeltaLocation);
        if (httpFileExists(segmentDeltaUrl)) {
          File segmentDeltaFile = new File(segmentFile.getAbsolutePath() + "_diff");
          try {
            downloadFile(segmentDeltaUrl, segmentDeltaFile, true);
            downloadProgressListener.updateProgress("Applying delta...");
            Rd5DiffTool.recoverFromDelta(segmentFile, segmentDeltaFile, segmentFileTemp, diffProgressListener);
          } catch (IOException e) {
            throw new IOException("Failed to download & apply delta update", e);
          } finally {
            segmentDeltaFile.delete();
          }
        }
      }

      if (!segmentFileTemp.exists()) {
        URL segmentUrl = new URL(segmentBaseUrl + segmentName);
        downloadFile(segmentUrl, segmentFileTemp, true);
      }

      PhysicalFile.checkFileIntegrity(segmentFileTemp);
      if (segmentFile.exists()) {
        if (!segmentFile.delete()) {
          throw new IOException("Failed to delete existing " + segmentFile.getAbsolutePath());
        }
      }

      if (!segmentFileTemp.renameTo(segmentFile)) {
        throw new IOException("Failed to write " + segmentFile.getAbsolutePath());
      }
    } finally {
      segmentFileTemp.delete();
    }
  }

  private boolean httpFileExists(URL downloadUrl) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
    connection.setConnectTimeout(5000);
    connection.setRequestMethod("HEAD");
    connection.connect();

    return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
  }

  private void downloadFile(URL downloadUrl, File outputFile, boolean limitDownloadSpeed) throws IOException, InterruptedException {
    // For all those small files the progress reporting is really noisy
    boolean reportDownloadProgress = limitDownloadSpeed;
    HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
    connection.setConnectTimeout(5000);
    connection.connect();

    if (reportDownloadProgress) downloadProgressListener.updateProgress("Connecting...");

    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      throw new IOException("HTTP Request failed");
    }
    int fileLength = connection.getContentLength();
    try (
      InputStream input = connection.getInputStream();
      OutputStream output = new FileOutputStream(outputFile)
    ) {
      byte[] buffer = new byte[4096];
      int total = 0;
      long t0 = System.currentTimeMillis();
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (isStopped()) {
          throw new InterruptedException();
        }
        total += count;
        output.write(buffer, 0, count);

        // publishing the progress....
        downloadProgressListener.updateProgress(fileLength, total);

        if (limitDownloadSpeed) {
          // enforce < 16 Mbit/s
          long dt = t0 + total / 2096 - System.currentTimeMillis();
          if (dt > 0) {
            Thread.sleep(dt);
          }
        }
      }
    }

    setProgressAsync(new Data.Builder().putInt(PROGRESS_SEGMENT_PERCENT, 100).build());
  }

  @NonNull
  private NotificationCompat.Builder createNotificationBuilder() {
    Context context = getApplicationContext();
    String id = context.getString(R.string.notification_channel_id);
    String title = context.getString(R.string.notification_title);
    String cancel = context.getString(R.string.cancel_download);
    // This PendingIntent can be used to cancel the worker
    PendingIntent intent = WorkManager.getInstance(context)
      .createCancelPendingIntent(getId());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createChannel();
    }

    return new NotificationCompat.Builder(context, id)
      .setContentTitle(title)
      .setTicker(title)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      // Add the cancel action to the notification which can
      // be used to cancel the worker
      .addAction(android.R.drawable.ic_delete, cancel, intent);
  }

  @NonNull
  private Notification createNotification(@NonNull String content) {
    notificationBuilder.setContentText(content);
    // Reset progress from previous download
    notificationBuilder.setProgress(0, 0, false);
    return notificationBuilder.build();
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private void createChannel() {
    CharSequence name = getApplicationContext().getString(R.string.channel_name);
    int importance = NotificationManager.IMPORTANCE_LOW;
    NotificationChannel channel = new NotificationChannel(getApplicationContext().getString(R.string.notification_channel_id), name, importance);
    // Register the channel with the system; you can't change the importance
    // or other notification behaviors after this
    notificationManager.createNotificationChannel(channel);
  }

  interface DownloadProgressListener {
    void updateProgress(int max, int progress);
    void updateProgress(String content);
  }
}
