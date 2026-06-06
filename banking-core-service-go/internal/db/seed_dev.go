package db

// Dev-only seed podaci — port account-service Liquibase changeset-a
// 003-seed-client-accounts.sql (context:dev). Seed-uje tekuce i FX racune za 8
// test klijenata (ID 1-8 iz client-service). Marko Markovic = client_id 1.
//
// Idempotentno: ON CONFLICT (broj_racuna) DO NOTHING, jer Go seed radi na svaki
// start (za razliku od Liquibase-a koji prati izvrsene changeset-ove).
const devSeedClientAccountsSQL = `
-- Marko Markovic (client_id = 1) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001100000000111', 'Marko', 'Markovic',
    'marko.markovic@banka.com', 'marko.markovic', 'Tekuci racun', 1, 1,
    100000.00, 100000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Ana Anic (client_id = 2) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001200000000111', 'Ana', 'Anic',
    'ana.anic@banka.com', 'ana.anic', 'Tekuci racun', 2, 1,
    150000.00, 150000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Jovana Jovanovic (client_id = 3) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001300000000111', 'Jovana', 'Jovanovic',
    'jovana.jovanovic@banka.com', 'jovana.jovanovic', 'Tekuci racun', 3, 1,
    80000.00, 80000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Jovana — EUR FX, PERSONAL
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001300000000221', 'Jovana', 'Jovanovic',
    NULL, NULL, 'Devizni racun EUR', 3, 1,
    2000.00, 2000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    5000.00, 20000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Stefan Stefanovic (client_id = 4) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001400000000111', 'Stefan', 'Stefanovic',
    'stefan.stefanovic@banka.com', 'stefan.stefanovic', 'Tekuci racun', 4, 1,
    200000.00, 200000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Stefan — USD FX, PERSONAL
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001400000000221', 'Stefan', 'Stefanovic',
    NULL, NULL, 'Devizni racun USD', 4, 1,
    3000.00, 3000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    5000.00, 20000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'USD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Milica Milic (client_id = 5) — STEDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001500000000113', 'Milica', 'Milic',
    'milica.milic@banka.com', 'milica.milic', 'Stedni racun', 5, 1,
    50000.00, 50000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STEDNI', 200.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Nikola Nikolic (client_id = 6) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001600000000111', 'Nikola', 'Nikolic',
    'nikola.nikolic@banka.com', 'nikola.nikolic', 'Tekuci racun', 6, 1,
    300000.00, 300000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Nikola — EUR FX, PERSONAL
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001600000000221', 'Nikola', 'Nikolic',
    NULL, NULL, 'Devizni racun EUR', 6, 1,
    5000.00, 5000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    5000.00, 20000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Jelena Jelic (client_id = 7) — ZA_MLADE RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001700000000115', 'Jelena', 'Jelic',
    'jelena.jelic@banka.com', 'jelena.jelic', 'Racun za mlade', 7, 1,
    25000.00, 25000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'ZA_MLADE', 150.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Aleksandar Aleksic (client_id = 8) — STANDARDNI RSD
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'CHECKING', '1110001800000000111', 'Aleksandar', 'Aleksic',
    'aleksandar.aleksic@banka.com', 'aleksandar.aleksic', 'Tekuci racun', 8, 1,
    500000.00, 500000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    250000.00, 1000000.00, 0.00, 0.00, NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Aleksandar — EUR FX, PERSONAL
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001800000000221', 'Aleksandar', 'Aleksic',
    NULL, NULL, 'Devizni racun EUR', 8, 1,
    10000.00, 10000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    5000.00, 20000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Aleksandar — USD FX, PERSONAL
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001800000000321', 'Aleksandar', 'Aleksic',
    NULL, NULL, 'Devizni racun USD', 8, 1,
    5000.00, 5000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    5000.00, 20000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'USD'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Marko — EUR FX account (mobile devizni racun screen)
INSERT INTO account_table (
    version, account_type, broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna, vlasnik, zaposlen, stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka, currency_id, status,
    dnevni_limit, mesecni_limit, dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT 0, 'FX', '1110001100000000221', 'Marko', 'Markovic',
    NULL, NULL, 'Devizni racun EUR', 1, 1,
    5000.00, 5000.00, NOW(), '2031-03-25', c.id, 'ACTIVE',
    10000.00, 50000.00, 0.00, 0.00, NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR'
ON CONFLICT (broj_racuna) DO NOTHING;

-- Marko — VISA DEBIT card (ACTIVE) on his RSD account
INSERT INTO cards (
    card_number, card_type, card_name, creation_date, expiration_date,
    account_number, client_id, authorized_person_id, cvv, card_limit, status
) VALUES (
    '4532015112830366', 'DEBIT', 'Visa Debit',
    CURRENT_DATE, CURRENT_DATE + INTERVAL '5 years',
    '1110001100000000111', 1, NULL,
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    50000.00, 'ACTIVE'
) ON CONFLICT (card_number) DO NOTHING;

-- Marko — Mastercard DEBIT (BLOCKED) on his RSD account — tests block/unblock toggle
INSERT INTO cards (
    card_number, card_type, card_name, creation_date, expiration_date,
    account_number, client_id, authorized_person_id, cvv, card_limit, status
) VALUES (
    '5425233430109903', 'DEBIT', 'Mastercard Debit',
    CURRENT_DATE, CURRENT_DATE + INTERVAL '5 years',
    '1110001100000000111', 1, NULL,
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    30000.00, 'BLOCKED'
) ON CONFLICT (card_number) DO NOTHING;

-- Marko — payment history (5 transactions with Ana Anic)
INSERT INTO payment_table (
    from_account_number, to_account_number,
    initial_amount, final_amount, commission,
    sender_client_id, recipient_client_id,
    recipient_name, payment_code, reference_number,
    payment_purpose, status, from_currency, to_currency, exchange_rate, created_at
) VALUES
  ('1110001100000000111','1110001200000000111', 5000,  5000,  0, 1, 2, 'Ana Anic',      '289','REF-001','Kirija',       'COMPLETED',   'RSD','RSD',NULL, NOW()-INTERVAL '28 days'),
  ('1110001100000000111','1110001200000000111', 12000, 12000, 0, 1, 2, 'Ana Anic',      '221','REF-002','Racun za gas', 'COMPLETED',   'RSD','RSD',NULL, NOW()-INTERVAL '21 days'),
  ('1110001100000000111','1110001200000000111', 3500,  3500,  0, 1, 2, 'Ana Anic',      '289','REF-003','Rata kredita', 'COMPLETED',   'RSD','RSD',NULL, NOW()-INTERVAL '14 days'),
  ('1110001200000000111','1110001100000000111', 8000,  8000,  0, 2, 1, 'Marko Markovic','289','REF-004','Povrat',       'COMPLETED',   'RSD','RSD',NULL, NOW()-INTERVAL '7 days'),
  ('1110001100000000111','1110001200000000111', 2000,  2000,  0, 1, 2, 'Ana Anic',      '289','REF-005','Hrana',        'IN_PROGRESS', 'RSD','RSD',NULL, NOW()-INTERVAL '1 day')
ON CONFLICT DO NOTHING;
`
