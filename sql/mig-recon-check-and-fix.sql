-- =====================================================================================
-- mig-recon-check-and-fix.sql   published recon snapshots after a migration: check, then rebuild the stale ones.
--
-- A snapshot is STALE when latest_active_lot was repaired after the snapshot was built (the builders read the
-- cache when they run). Run mig-lal-check-and-fix.sql FIRST: this block refuses to rebuild while the cache differs.
--
-- HOW TO RUN (DBeaver, connected to the database you mean, auto-commit ON):
--   1. Run the whole file as a script (Alt+X), Output tab. Default = check only (fix = false).
--   2. MISMATCH: set fix := true and run again. Rebuilds are slow (CFS settle 10-15 min), so do ONE BU at a time:
--      set only_bu := 'CFS' (then the next BU), or leave NULL for all. The whole run is one transaction: a failure
--      rolls back what that run did, so a smaller scope loses less.
--   3. BUs with no published snapshot are listed: nothing to rebuild there, run Refresh Recon in the app.
-- SAFE: check mode changes no data. Fix mode deletes and rebuilds only derived snapshot rows of the published run,
--   with the same advisory lock as the app refresh (it stops with a message if the app is refreshing). Rerunnable:
--   a rebuilt snapshot is no longer stale.
-- =====================================================================================
SET statement_timeout = 0;
SET lock_timeout = '30s';
SET work_mem = '256MB';
SET synchronous_commit = off;

DO $$
DECLARE
  fix     boolean := false;   -- false = check only, true = check and rebuild
  only_bu text    := NULL;    -- e.g. 'CFS' for one BU, NULL for all
  r record; n int := 0; v_rows bigint; v_cnt bigint; v_tbl text; bad int; nosnap text;
BEGIN
  RAISE NOTICE 'Database: % @ %  (fix = %, only_bu = %)', current_database(), coalesce(inet_server_addr()::text, 'local') || ':' || inet_server_port(), fix, coalesce(only_bu, 'all');
  PERFORM set_config('search_path', 'opera, public', true);

  IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'opera'::regnamespace AND proname = 'fn_latest_active_lot_reconcile') THEN
    RAISE EXCEPTION 'opera.fn_latest_active_lot_reconcile is not installed (transaction-core PR 177). Nothing was changed.';
  END IF;
  SELECT count(*) INTO bad FROM opera.fn_latest_active_lot_reconcile(NULL, NULL, false, 'recon precheck')
  WHERE missing + extra + different > 0;
  IF bad > 0 THEN
    RAISE EXCEPTION 'latest_active_lot still differs in % BU(s): run mig-lal-check-and-fix.sql first. Nothing was changed.', bad;
  END IF;

  FOR r IN
    SELECT p.tenant_id, p.business_unit, p.view_type, p.settlement_date, p.published_run_id, rr.resolved_settlement_date
    FROM opera.recon_snapshot_pointer p
    JOIN opera.recon_refresh_run rr ON rr.uuid = p.published_run_id
    WHERE (only_bu IS NULL OR p.business_unit = only_bu)
      AND p.settlement_date = (SELECT max(p2.settlement_date) FROM opera.recon_snapshot_pointer p2
                               WHERE p2.tenant_id = p.tenant_id AND p2.business_unit = p.business_unit AND p2.view_type = p.view_type)
      AND EXISTS (SELECT 1 FROM opera.latest_active_lot_reconcile_log l
                  WHERE l.tenant_id = p.tenant_id AND l.business_unit = p.business_unit AND l.repaired
                    AND (l.missing + l.extra + l.different) > 0
                    AND l.run_at > greatest(rr.completed_at, rr.updated_timestamp))
    ORDER BY p.business_unit, p.view_type
  LOOP
    n := n + 1;
    IF NOT fix THEN
      RAISE NOTICE 'STALE: % % (settlement %)', r.business_unit, r.view_type, r.settlement_date;
      CONTINUE;
    END IF;
    IF NOT pg_try_advisory_xact_lock(hashtextextended(r.tenant_id || '|' || r.business_unit || '|' || r.view_type || '|' || r.resolved_settlement_date::text, 0)) THEN
      RAISE EXCEPTION 'the app is refreshing % %: try again later', r.business_unit, r.view_type;
    END IF;
    IF r.view_type = 'SETTLE_PRICING' THEN
      v_rows := opera.recon_materialize_settle(r.published_run_id);  PERFORM opera.recon_postprocess_settle(r.published_run_id);  v_tbl := 'recon_settle_snapshot';
    ELSIF r.view_type = 'SETTLE_ACTUAL_PRICING' THEN
      v_rows := opera.recon_materialize_actual(r.published_run_id);  PERFORM opera.recon_postprocess_actual(r.published_run_id);  v_tbl := 'recon_actual_snapshot';
    ELSE
      v_rows := opera.recon_materialize_traders(r.published_run_id); PERFORM opera.recon_postprocess_traders(r.published_run_id); v_tbl := 'recon_traders_snapshot';
    END IF;
    EXECUTE format('SELECT count(*) FROM opera.%I WHERE run_id = $1 AND tenant_id = $2 AND business_unit = $3 AND settlement_date = $4', v_tbl)
      INTO v_cnt USING r.published_run_id, r.tenant_id, r.business_unit, r.resolved_settlement_date;
    IF v_rows IS DISTINCT FROM v_cnt THEN
      RAISE EXCEPTION 'validation failed for % %: built % rows, stored %', r.business_unit, r.view_type, v_rows, v_cnt;
    END IF;
    UPDATE opera.recon_refresh_run SET row_count = v_rows, updated_timestamp = now() WHERE uuid = r.published_run_id;
    RAISE NOTICE 'REBUILT: % % : % rows', r.business_unit, r.view_type, v_rows;
  END LOOP;

  SELECT string_agg(v.business_unit, ', ' ORDER BY v.business_unit) INTO nosnap
  FROM (SELECT DISTINCT business_unit, tenant_id FROM opera.valuation_date_table) v
  WHERE (only_bu IS NULL OR v.business_unit = only_bu)
    AND NOT EXISTS (SELECT 1 FROM opera.recon_snapshot_pointer p WHERE p.tenant_id = v.tenant_id AND p.business_unit = v.business_unit);
  IF nosnap IS NOT NULL THEN
    RAISE NOTICE 'NO SNAPSHOT yet (run Refresh Recon in the app, nothing to rebuild): %', nosnap;
  END IF;

  IF n = 0 THEN
    RAISE NOTICE 'OK: no published recon snapshot is older than a latest_active_lot repair.';
  ELSIF NOT fix THEN
    RAISE NOTICE 'MISMATCH: % snapshot(s) are stale. NEXT: set fix := true and run again (set only_bu for one BU at a time).', n;
  ELSE
    RAISE NOTICE 'FIXED: % snapshot(s) rebuilt.', n;
  END IF;
END $$;
