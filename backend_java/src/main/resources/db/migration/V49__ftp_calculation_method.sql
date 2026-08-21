-- Per-athlete choice of how ThresholdHistoryCalculator derives an implied FTP from a bike
-- activity: the conventional 20-minute-test estimate (best 20-min power * 0.95), or the best
-- 60-minute power directly (FTP's own textbook definition, no multiplier).
ALTER TABLE users ADD COLUMN ftp_calculation_method VARCHAR(20) NOT NULL DEFAULT 'twenty_min_test'
    CHECK (ftp_calculation_method IN ('twenty_min_test', 'sixty_min_direct'));
