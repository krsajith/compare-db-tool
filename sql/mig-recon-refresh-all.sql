-- =====================================================================================
-- mig-recon-refresh-all.sql   Recon snapshots for ALL BUs after a migration, in one script (replaces clicking Refresh Recon).
--
-- It does what the app does for each BU and view type (ReconRefreshService + ReconMaterializationService):
--   resolve the dates (recon_resolve_date_context) -> insert the recon_refresh_run row with the profile key and processor plan
--   (same rules and order as ReconProfileResolver) -> same advisory lock -> recon_materialize_* -> recon_postprocess_* ->
--   check built rows = stored rows -> mark the run COMPLETED -> publish recon_snapshot_pointer.
-- Per BU and view type it decides:
--   NEW    no published snapshot for the BU settlement date  -> create the run and build it
--   STALE  published, but latest_active_lot was repaired after it was built -> rebuild it in place (same run id)
--   OK     published and current -> left alone
-- Views: SETTLE_PRICING for every BU; SETTLE_ACTUAL_PRICING for GPC, RBA, RBC, RMC when they have actual values;
-- TRADERS_PRICING for BUs that have trader values on the value date. Edit  views  below to restrict.
--
-- HOW TO RUN (DBeaver connected to the database you mean, AUTO-COMMIT ON, Output tab open):
--   1. Run the whole file as a script (Alt+X). Default = check only: it lists NEW / STALE per BU and view and changes nothing.
--   2. Test on one small BU first: set only_bu := 'PF6' and fix := true, run, look at the notices. Then fix := true with only_bu NULL.
--   3. Rerun any time: finished combinations are OK and skipped, so an interrupted run just continues.
-- SAFE: check mode writes nothing. Fix mode COMMITS after each BU and view type (a failure loses only that one, finished ones stay).
--   It refuses to start while latest_active_lot differs (run mig-lal-check-and-fix.sql first) and stops if the app is refreshing.
--   Slow: CFS settle 10-15 min, so a full run takes hours. Run in a quiet window; it can be stopped and restarted.
-- =====================================================================================
SET statement_timeout = 0;
SET lock_timeout = '30s';
SET work_mem = '256MB';
SET synchronous_commit = off;
SET search_path TO opera, public;

DO $$
DECLARE
  fix      boolean := false;   -- false = check only, true = check and build
  only_bu  text    := NULL;    -- e.g. 'PF6' for one BU, NULL for all
  views    text[]  := ARRAY['SETTLE_PRICING', 'SETTLE_ACTUAL_PRICING', 'TRADERS_PRICING'];
  who      text    := 'migration-sql';
  r record; ctx record; ex record;
  v_vt text; v_state text; v_run uuid; v_rows bigint; v_cnt bigint; v_tbl text; v_plan jsonb; v_key text; v_t timestamptz;
  bad int; n_new int := 0; n_stale int := 0; n_ok int := 0; n_skip int := 0; n_done int := 0;
BEGIN
  RAISE NOTICE 'Database: %  (fix = %, only_bu = %)', current_database(), fix, coalesce(only_bu, 'all');
  IF NOT EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'opera'::regnamespace AND proname = 'fn_latest_active_lot_reconcile') THEN
    RAISE EXCEPTION 'opera.fn_latest_active_lot_reconcile is not installed (transaction-core PR 177). Nothing was changed.';
  END IF;
  SELECT count(*) INTO bad FROM opera.fn_latest_active_lot_reconcile(NULL, NULL, false, 'recon refresh precheck')
  WHERE missing + extra + different > 0;
  IF bad > 0 THEN
    RAISE EXCEPTION 'latest_active_lot still differs in % BU(s): run mig-lal-check-and-fix.sql first. Nothing was changed.', bad;
  END IF;

  FOR r IN
    SELECT DISTINCT ON (v.tenant_id, v.business_unit) v.tenant_id, v.business_unit, v.settlement_date AS req_date
    FROM opera.valuation_date_table v
    WHERE only_bu IS NULL OR v.business_unit = only_bu
    ORDER BY v.tenant_id, v.business_unit, v.value_date DESC
  LOOP
    SELECT c.settlement_date, c.value_date INTO ctx FROM opera.recon_resolve_date_context(r.tenant_id, r.business_unit, r.req_date) c;
    IF ctx.settlement_date IS NULL OR ctx.value_date IS NULL THEN
      RAISE NOTICE 'SKIP % : no valuation-date mapping (the app would reject it too)', r.business_unit;
      n_skip := n_skip + 1;
      CONTINUE;
    END IF;

    FOREACH v_vt IN ARRAY views LOOP
      -- inputs: skip a view the BU has no data for
      IF v_vt = 'SETTLE_ACTUAL_PRICING' AND NOT (r.business_unit IN ('GPC', 'RBA', 'RBC', 'RMC') AND EXISTS (
           SELECT 1 FROM opera.security_last_actual_value_table a
           WHERE a.tenant_id = r.tenant_id AND a.business_unit = r.business_unit AND a.value_date = ctx.value_date)) THEN
        n_skip := n_skip + 1; CONTINUE;
      END IF;
      IF v_vt = 'TRADERS_PRICING' AND NOT EXISTS (
           SELECT 1 FROM opera.security_traders_value_table t
           WHERE t.tenant_id = r.tenant_id AND t.business_unit = r.business_unit AND t.value_date = ctx.value_date) THEN
        n_skip := n_skip + 1; CONTINUE;
      END IF;

      -- state of the published snapshot for this BU, view and settlement date
      ex := NULL;
      SELECT p.published_run_id, rr.completed_at, rr.updated_timestamp INTO ex
      FROM opera.recon_snapshot_pointer p JOIN opera.recon_refresh_run rr ON rr.uuid = p.published_run_id
      WHERE p.tenant_id = r.tenant_id AND p.business_unit = r.business_unit AND p.view_type = v_vt AND p.settlement_date = ctx.settlement_date;
      IF ex.published_run_id IS NULL THEN
        v_state := 'NEW';
      ELSIF EXISTS (SELECT 1 FROM opera.latest_active_lot_reconcile_log l
                    WHERE l.tenant_id = r.tenant_id AND l.business_unit = r.business_unit AND l.repaired
                      AND (l.missing + l.extra + l.different) > 0
                      AND l.run_at > greatest(ex.completed_at, ex.updated_timestamp)) THEN
        v_state := 'STALE';
      ELSE
        v_state := 'OK';
      END IF;

      IF v_state = 'OK' THEN n_ok := n_ok + 1; CONTINUE; END IF;
      IF v_state = 'NEW' THEN n_new := n_new + 1; ELSE n_stale := n_stale + 1; END IF;
      IF NOT fix THEN
        RAISE NOTICE '%: % % (settlement %)', v_state, r.business_unit, v_vt, ctx.settlement_date;
        CONTINUE;
      END IF;

      -- ---- build (same as the app) ----
      IF NOT pg_try_advisory_xact_lock(hashtextextended(r.tenant_id || '|' || r.business_unit || '|' || v_vt || '|' || ctx.settlement_date::text, 0)) THEN
        RAISE EXCEPTION 'the app is refreshing % %: try again later', r.business_unit, v_vt;
      END IF;
      v_t := clock_timestamp();
      IF v_state = 'NEW' THEN
        v_run := gen_random_uuid();
        -- profile key and processor plan exactly as ReconProfileResolver (same rules, ReconPostProcessor enum order)
        SELECT COALESCE(jsonb_agg(p.name ORDER BY p.pos), '[]'::jsonb),
               CASE WHEN bool_or(p.pos NOT IN (1, 2, 3)) THEN 'LEGACY_' || r.business_unit || '_' || v_vt ELSE 'DEFAULT_' || v_vt END
          INTO v_plan, v_key
        FROM (VALUES
          ( 1, 'NORMALISE_CURRENCY',                r.business_unit NOT IN ('QF5','PG7','PG8')),
          ( 2, 'NORMALISE_CURRENCY_EXCEPT_P2_NAME', r.business_unit IN ('QF5','PG7','PG8')),
          ( 3, 'APPEND_BUSINESS_UNIT_TO_TRADE_ID',  true),
          ( 4, 'APPLY_THICK_FX_RULES',              v_vt = 'SETTLE_PRICING' AND r.business_unit IN ('CFS','ORM','ROV')),
          ( 5, 'POPULATE_CORPORATE_ACTION_FIELDS',  v_vt = 'SETTLE_PRICING' AND r.business_unit IN ('QF5','PG7','PG8')),
          ( 6, 'CONVERT_CNY_MEASURES',              (v_vt = 'SETTLE_PRICING' AND r.business_unit IN ('RMC','OSG'))
                                                 OR (v_vt = 'SETTLE_ACTUAL_PRICING' AND r.business_unit = 'RMC')),
          ( 7, 'APPLY_SCICOM_CORRELATION',          v_vt = 'SETTLE_PRICING' AND r.business_unit = 'CFS'),
          ( 8, 'STORE_MARKET_IMPACT',               v_vt = 'SETTLE_PRICING' AND r.business_unit IN ('CFS','CTO','ORM','RBA','RBC','RMC')),
          ( 9, 'BUILD_POSITION_RECON',              v_vt IN ('SETTLE_PRICING','SETTLE_ACTUAL_PRICING') AND r.business_unit IN ('RBA','RBC')),
          (10, 'APPLY_RBH_CASH_BALANCE_RULES',      v_vt = 'SETTLE_PRICING' AND r.business_unit = 'RBH'),
          (11, 'APPLY_CTO_PREMIUM_DIFFERENCE',      v_vt = 'SETTLE_PRICING' AND r.business_unit = 'CTO'),
          (12, 'APPLY_GPC_PREMIUM_DIFFERENCE',      v_vt = 'SETTLE_PRICING' AND r.business_unit = 'GPC'),
          (13, 'APPLY_BKR_CORPORATE_ACTION_RULES',  v_vt = 'SETTLE_PRICING' AND r.business_unit = 'BKR')
        ) p(pos, name, applies) WHERE p.applies;

        INSERT INTO opera.recon_refresh_run (uuid, tenant_id, business_unit, view_type, requested_settlement_date,
               resolved_settlement_date, resolved_value_date, profile_key, profile_version, processor_plan,
               status, requested_by, requested_at, started_at, created_by, updated_by, created_timestamp, updated_timestamp)
        VALUES (v_run, r.tenant_id, r.business_unit, v_vt, r.req_date, ctx.settlement_date, ctx.value_date,
               v_key, 1, v_plan, 'RUNNING', who, now(), now(), who, who, now(), now());
      ELSE
        v_run := ex.published_run_id;
      END IF;

      IF v_vt = 'SETTLE_PRICING' THEN
        v_rows := opera.recon_materialize_settle(v_run);  PERFORM opera.recon_postprocess_settle(v_run);  v_tbl := 'recon_settle_snapshot';
      ELSIF v_vt = 'SETTLE_ACTUAL_PRICING' THEN
        v_rows := opera.recon_materialize_actual(v_run);  PERFORM opera.recon_postprocess_actual(v_run);  v_tbl := 'recon_actual_snapshot';
      ELSE
        v_rows := opera.recon_materialize_traders(v_run); PERFORM opera.recon_postprocess_traders(v_run); v_tbl := 'recon_traders_snapshot';
      END IF;
      EXECUTE format('SELECT count(*) FROM opera.%I WHERE run_id = $1 AND tenant_id = $2 AND business_unit = $3 AND settlement_date = $4', v_tbl)
        INTO v_cnt USING v_run, r.tenant_id, r.business_unit, ctx.settlement_date;
      IF v_rows IS DISTINCT FROM v_cnt THEN
        RAISE EXCEPTION 'validation failed for % %: built % rows, stored %', r.business_unit, v_vt, v_rows, v_cnt;
      END IF;

      UPDATE opera.recon_refresh_run SET status = 'COMPLETED', row_count = v_rows, completed_at = now(), updated_by = who, updated_timestamp = now()
      WHERE uuid = v_run;
      INSERT INTO opera.recon_snapshot_pointer (tenant_id, business_unit, view_type, settlement_date, published_run_id, published_at,
             created_by, updated_by, created_timestamp, updated_timestamp)
      VALUES (r.tenant_id, r.business_unit, v_vt, ctx.settlement_date, v_run, clock_timestamp(), who, who, clock_timestamp(), clock_timestamp())
      ON CONFLICT (tenant_id, business_unit, view_type, settlement_date)
      DO UPDATE SET published_run_id = excluded.published_run_id, published_at = clock_timestamp(),
                    updated_by = excluded.updated_by, updated_timestamp = clock_timestamp();
      n_done := n_done + 1;
      RAISE NOTICE 'BUILT (%): % % : % rows in % s', lower(v_state), r.business_unit, v_vt, v_rows, round(extract(epoch FROM clock_timestamp() - v_t)::numeric, 1);
      COMMIT;   -- keep this BU and view even if a later one fails; also releases the advisory lock
    END LOOP;
  END LOOP;

  IF n_new + n_stale = 0 THEN
    RAISE NOTICE 'OK: every BU has a current published recon snapshot (% up to date, % skipped).', n_ok, n_skip;
  ELSIF NOT fix THEN
    RAISE NOTICE 'MISMATCH: % snapshot(s) missing and % stale (% up to date, % skipped). NEXT: set fix := true and run again (test with only_bu first).', n_new, n_stale, n_ok, n_skip;
  ELSE
    RAISE NOTICE 'FIXED: % snapshot(s) built (% new, % rebuilt). % up to date, % skipped.', n_done, n_new, n_stale, n_ok, n_skip;
  END IF;
END $$;
