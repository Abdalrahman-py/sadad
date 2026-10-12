package com.sadad.app;
import android.app.Instrumentation;import android.os.Bundle;import android.database.sqlite.SQLiteDatabase;import org.json.*;
public final class Scale255Instrumentation extends Instrumentation {
 void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();SadadDatabase ledger=null;try{
  check(getTargetContext().getPackageName().contains(".qa"),"QA only");
  getTargetContext().deleteDatabase("scale255.db");ledger=new SadadDatabase(getTargetContext(),"scale255.db");
  long person=ledger.saveContact(new JSONObject().put("name","اختبار كامل")).getLong("id");
  ledger.saveDebt(new JSONObject().put("contactId",person).put("amount","100"));
  ledger.saveDebt(new JSONObject().put("contactId",person).put("amount","25"));
  SQLiteDatabase db=ledger.getWritableDatabase();db.beginTransaction();try{for(int i=0;i<2100;i++)db.execSQL("INSERT INTO debts(contact_id,direction,amount_cents,note,due_date,created_by,created_at) VALUES(?,'receivable',100,'','','',?)",new Object[]{person,System.currentTimeMillis()});db.setTransactionSuccessful();}finally{db.endTransaction();}
  JSONObject overview=ledger.getOverview(),all=ledger.getSnapshot();check(overview.getJSONArray("debts").length()==1000,"Overview not bounded");check(all.getJSONArray("debts").length()==2102,"Full history cut");check(Math.abs(all.getJSONObject("totals").getDouble("receivable")-2225)<0.001,"Complete balance incorrect");check(Math.abs(overview.getJSONObject("totals").getDouble("receivable")-2225)<0.001,"Overview lost older balance");
  result.putString("stream","PASS: bounded overview, 2102 debts retained and balance 2225\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","FAIL: "+android.util.Log.getStackTraceString(e));finish(0,result);}finally{if(ledger!=null)ledger.close();}}
}
