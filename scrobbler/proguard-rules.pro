# WorkManager păstrează numele clasei în coada existentă, inclusiv peste update.
-keep class ro.ak.vodscrobbler.NetflixRecoveryWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
