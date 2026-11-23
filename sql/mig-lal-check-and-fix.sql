-- =====================================================================================
-- mig-lal-check-and-fix.sql   latest_active_lot after a migration: check, then correct.
--
-- HOW TO RUN (DBeaver, connected to the database you mean, auto-commit ON):
--   1. Select ALL of this file and run it as a script (Alt+X). Open the "Output" tab.
--      It only CHECKS (fix = false) and prints the database name, then OK or MISMATCH.
--   2. If it says MISMATCH: change  fix boolean := false  to  true  and run again (Alt+X).
--   3. Then run mig-recon-check-and-fix.sql.
-- SAFE: check mode changes no data (it only adds a row to latest_active_lot_reconcile_log).
--   Fix mode writes only the rows that differ, then ANALYZE. Rerunnable.
-- FIX MODE LOCKS each BU until it ends: run when nobody is saving deals. About 1 s per 200k trades.
-- Needs the reconcile function from transaction-core PR 177 (the block stops with a message if missing).
-- =====================================================================================
SET statement_timeout = 0;
SET lock_timeout = '30s';
SET opera.lal_exclusive_wait_seconds = 120;

DO $$
DECLARE
  fix  boolean := false;      -- false = check only, true = check and correct
  bus  int; m bigint; e bigint; d bigint;
BEGIN
  RAISE NOTICE 'Database: % @ %  (fix = %)', current_database(), coalesce(inet_server_addr()::text, 'local') || ':' || inet_server_port(), fix;
  IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'opera'::regnamespace AND proname = 'fn_latest_active_lot_reconcile') THEN
    RAISE EXCEPTION 'opera.fn_latest_active_lot_reconcile is not installed (transaction-core PR 177). Nothing was changed.';
  END IF;

  SELECT count(*), coalesce(sum(missing),0), coalesce(sum(extra),0), coalesce(sum(different),0)
    INTO bus, m, e, d
  FROM opera.fn_latest_active_lot_reconcile(NULL, NULL, false, 'simple check')
  WHERE missing + extra + different > 0;

  IF bus = 0 THEN
    RAISE NOTICE 'OK: latest_active_lot matches deal_active_lots_table for every BU.';
    RETURN;
  END IF;

  RAISE NOTICE 'MISMATCH: % BU(s): % trades missing, % extra, % different.', bus, m, e, d;
  IF NOT fix THEN
    RAISE NOTICE 'NEXT: change fix to true at the top of this block and run it again.';
    RETURN;
  END IF;

  PERFORM * FROM opera.fn_latest_active_lot_reconcile(NULL, NULL, true, 'simple fix');
  EXECUTE 'ANALYZE opera.latest_active_lot';

  SELECT count(*) INTO bus FROM opera.fn_latest_active_lot_reconcile(NULL, NULL, false, 'simple verify')
  WHERE missing + extra + different > 0;
  IF bus = 0 THEN
    RAISE NOTICE 'FIXED: latest_active_lot now matches for every BU. NEXT: run mig-recon-check-and-fix.sql';
  ELSE
    RAISE EXCEPTION 'STILL MISMATCH after the fix in % BU(s). Run rules\mig-lal-01-check.xml for details.', bus;
  END IF;
END $$;
