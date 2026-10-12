package com.sadad.app;
import android.app.job.*;
import java.util.concurrent.*;

/** No UI or foreground-service requirement: constrained work runs on a single worker. */
public final class LedgerSyncService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<Integer,Future<?>> tasks=new java.util.concurrent.ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        if(BuildConfig.STANDALONE_MODE)return false;
        MainActivity activity=MainActivity.activeInstance.get();
        if(activity!=null){activity.syncTick();return false;}
        Future<?> task=worker.submit(()->{boolean retry=false;
            synchronized(SyncScheduler.LOCK){
                SessionStore session=new SessionStore(this);SadadDatabase db=null;
                try{
                    if(!session.isActive()||session.forcePasswordChange()||session.syncBlocked())return;
                    String account=session.syncAccountId();if(session.token().isEmpty())return;
                    db=new SadadDatabase(this,session.databaseNameForAccount(account));
                    if("v3".equals(session.ledgerMode())){
                        // A signed-ledger store: the queue is the outbox, and the ledger's snapshot is
                        // folded into the same local tables the screens read. A device still waiting
                        // for admin approval is not a failure, so it is not retried in a tight loop.
                        LedgerQueue queue=LedgerQueue.open(this,session);
                        try{
                            SignedLedgerSync.Result result=queue.sync();
                            try{db.importLedgerView(queue.view());}catch(Exception skipped){}
                            retry=result.retryable();
                        }finally{queue.close();}
                    }else{
                        if(db.hasPendingSync())session.setUnsyncedChanges(true);
                        // The protocol lives in LedgerSync: delta-versus-full, the pull, and what each refusal
                        // means. This job only maps the outcome onto its retry flag.
                        LedgerSync.Result result=LedgerSync.sync(session,db,new HttpSyncTransport(session));
                        retry=result.repeat();
                    }
                    DueReminderManager.checkDueDates(this);
                }catch(Exception e){retry=true;}
                finally{if(db!=null)db.close();tasks.remove(params.getJobId());jobFinished(params,retry);}
            }
        });tasks.put(params.getJobId(),task);return true;
    }
    @Override public boolean onStopJob(JobParameters params){Future<?> task=tasks.remove(params.getJobId());if(task!=null)task.cancel(true);return true;}
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
