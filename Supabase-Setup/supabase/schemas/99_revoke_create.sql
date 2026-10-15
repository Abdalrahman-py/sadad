-- Last file: the internal roles needed CREATE only to own their routines.
revoke create on schema sadid_private from sadid_ledger_executor, sadid_auth_reader, sadid_support_reader;
