-- DEV-ONLY seed: loans and installments for Marko Markovic (client_id=1).
-- Provides realistic data for the mobile Krediti screen:
--   Loan 1 — PERSONAL (RSD), 24 months, 6 installments paid, ACTIVE
--   Loan 2 — AUTO    (RSD), 36 months, 3 installments paid, ACTIVE
--   Loan 3 — STUDENT (RSD), 12 months, fully repaid,        PAID_OFF
--   Loan 4 — MORTGAGE(EUR), 120 months, 2 installments paid, ACTIVE
-- All INSERT … WHERE NOT EXISTS so the file is idempotent on re-runs.

-- ============================================================================
-- Loan 1: Personal cash loan (RSD), 24 months, 6 paid
-- ============================================================================
INSERT INTO loan_table (
    loan_type, account_number, amount, repayment_period,
    nominal_interest_rate, effective_interest_rate, interest_type,
    agreement_date, maturity_date, installment_amount,
    next_installment_date, remaining_debt, currency,
    status, user_email, username, client_id, installment_count
)
SELECT
    'PERSONAL', '1110001100000000111', 300000.00, 24,
    0.0850000000, 0.0884000000, 'FIXED',
    CURRENT_DATE - INTERVAL '6 months',
    CURRENT_DATE + INTERVAL '18 months',
    14250.00,
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    214500.00, 'RSD',
    'ACTIVE', 'marko.markovic@banka.com', 'Marko Markovic', 1, 6
WHERE NOT EXISTS (
    SELECT 1 FROM loan_table WHERE client_id=1 AND loan_type='PERSONAL' AND amount=300000.00
);

-- ============================================================================
-- Loan 2: Auto loan (RSD), 36 months, 3 paid
-- ============================================================================
INSERT INTO loan_table (
    loan_type, account_number, amount, repayment_period,
    nominal_interest_rate, effective_interest_rate, interest_type,
    agreement_date, maturity_date, installment_amount,
    next_installment_date, remaining_debt, currency,
    status, user_email, username, client_id, installment_count
)
SELECT
    'AUTO', '1110001100000000111', 800000.00, 36,
    0.0720000000, 0.0745000000, 'FIXED',
    CURRENT_DATE - INTERVAL '3 months',
    CURRENT_DATE + INTERVAL '33 months',
    24800.00,
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    725600.00, 'RSD',
    'ACTIVE', 'marko.markovic@banka.com', 'Marko Markovic', 1, 3
WHERE NOT EXISTS (
    SELECT 1 FROM loan_table WHERE client_id=1 AND loan_type='AUTO' AND amount=800000.00
);

-- ============================================================================
-- Loan 3: Student loan (RSD), 12 months, fully paid off
-- ============================================================================
INSERT INTO loan_table (
    loan_type, account_number, amount, repayment_period,
    nominal_interest_rate, effective_interest_rate, interest_type,
    agreement_date, maturity_date, installment_amount,
    next_installment_date, remaining_debt, currency,
    status, user_email, username, client_id, installment_count
)
SELECT
    'STUDENT', '1110001100000000111', 120000.00, 12,
    0.0450000000, 0.0460000000, 'FIXED',
    CURRENT_DATE - INTERVAL '13 months',
    CURRENT_DATE - INTERVAL '1 month',
    10200.00,
    CURRENT_DATE - INTERVAL '1 month',
    0.00, 'RSD',
    'PAID_OFF', 'marko.markovic@banka.com', 'Marko Markovic', 1, 12
WHERE NOT EXISTS (
    SELECT 1 FROM loan_table WHERE client_id=1 AND loan_type='STUDENT' AND amount=120000.00
);

-- ============================================================================
-- Loan 4: Mortgage (EUR FX account), 120 months, 2 paid
-- ============================================================================
INSERT INTO loan_table (
    loan_type, account_number, amount, repayment_period,
    nominal_interest_rate, effective_interest_rate, interest_type,
    agreement_date, maturity_date, installment_amount,
    next_installment_date, remaining_debt, currency,
    status, user_email, username, client_id, installment_count
)
SELECT
    'MORTGAGE', '1110001100000000221', 85000.00, 120,
    0.0310000000, 0.0315000000, 'VARIABLE',
    CURRENT_DATE - INTERVAL '2 months',
    CURRENT_DATE + INTERVAL '118 months',
    835.00,
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    83330.00, 'EUR',
    'ACTIVE', 'marko.markovic@banka.com', 'Marko Markovic', 1, 2
WHERE NOT EXISTS (
    SELECT 1 FROM loan_table WHERE client_id=1 AND loan_type='MORTGAGE' AND amount=85000.00
);

-- ============================================================================
-- Installments for Loan 1 (PERSONAL): 6 paid + 1 upcoming
-- ============================================================================
INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id,
    14250.00, 0.0884000000, 'RSD',
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '6 months') + INTERVAL '1 month' * gs.n)::date,
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '6 months') + INTERVAL '1 month' * gs.n)::date,
    'PAID', 0
FROM loan_table l, generate_series(1,6) AS gs(n)
WHERE l.client_id=1 AND l.loan_type='PERSONAL' AND l.amount=300000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='PAID');

INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id, 14250.00, 0.0884000000, 'RSD',
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    NULL, 'UNPAID', 0
FROM loan_table l
WHERE l.client_id=1 AND l.loan_type='PERSONAL' AND l.amount=300000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='UNPAID');

-- ============================================================================
-- Installments for Loan 2 (AUTO): 3 paid + 1 upcoming
-- ============================================================================
INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id,
    24800.00, 0.0745000000, 'RSD',
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '3 months') + INTERVAL '1 month' * gs.n)::date,
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '3 months') + INTERVAL '1 month' * gs.n)::date,
    'PAID', 0
FROM loan_table l, generate_series(1,3) AS gs(n)
WHERE l.client_id=1 AND l.loan_type='AUTO' AND l.amount=800000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='PAID');

INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id, 24800.00, 0.0745000000, 'RSD',
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    NULL, 'UNPAID', 0
FROM loan_table l
WHERE l.client_id=1 AND l.loan_type='AUTO' AND l.amount=800000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='UNPAID');

-- ============================================================================
-- Installments for Loan 3 (STUDENT): all 12 paid
-- ============================================================================
INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id,
    10200.00, 0.0460000000, 'RSD',
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '13 months') + INTERVAL '1 month' * gs.n)::date,
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '13 months') + INTERVAL '1 month' * gs.n)::date,
    'PAID', 0
FROM loan_table l, generate_series(1,12) AS gs(n)
WHERE l.client_id=1 AND l.loan_type='STUDENT' AND l.amount=120000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id);

-- ============================================================================
-- Installments for Loan 4 (MORTGAGE): 2 paid + 1 upcoming
-- ============================================================================
INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id,
    835.00, 0.0315000000, 'EUR',
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '2 months') + INTERVAL '1 month' * gs.n)::date,
    (DATE_TRUNC('month', CURRENT_DATE - INTERVAL '2 months') + INTERVAL '1 month' * gs.n)::date,
    'PAID', 0
FROM loan_table l, generate_series(1,2) AS gs(n)
WHERE l.client_id=1 AND l.loan_type='MORTGAGE' AND l.amount=85000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='PAID');

INSERT INTO installment_table (loan_id, installment_amount, interest_rate_at_payment, currency, expected_due_date, actual_due_date, payment_status, retry)
SELECT l.id, 835.00, 0.0315000000, 'EUR',
    (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month')::date,
    NULL, 'UNPAID', 0
FROM loan_table l
WHERE l.client_id=1 AND l.loan_type='MORTGAGE' AND l.amount=85000.00
  AND NOT EXISTS (SELECT 1 FROM installment_table i WHERE i.loan_id=l.id AND i.payment_status='UNPAID');
