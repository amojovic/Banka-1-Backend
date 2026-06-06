-- DEV-ONLY seed: 30 days of historical exchange rates for the mobile FX chart.
-- Without this, the chart only shows today's single data point (flat line).
-- Rates drift ±0.5% per day from today's baseline using a repeating 7-day cycle
-- so the chart looks realistic without needing live API data.
-- ON CONFLICT DO NOTHING — safe to re-run if today's rates already exist.

INSERT INTO exchange_rate (currency_code, buying_rate, selling_rate, rate_date)
SELECT
    c.code,
    ROUND((c.buying  * (1 + (EXTRACT(DOY FROM gs.d)::numeric % 7 - 3) * 0.002))::numeric, 8),
    ROUND((c.selling * (1 + (EXTRACT(DOY FROM gs.d)::numeric % 7 - 3) * 0.002))::numeric, 8),
    gs.d::date
FROM
    generate_series(
        CURRENT_DATE - INTERVAL '30 days',
        CURRENT_DATE - INTERVAL '1 day',
        '1 day'::interval
    ) AS gs(d),
    (VALUES
        ('EUR', 116.1982008::numeric, 118.5456392::numeric),
        ('USD', 100.7318169::numeric, 102.7668031::numeric),
        ('CHF', 126.6239205::numeric, 129.1819795::numeric),
        ('GBP', 134.5736799::numeric, 137.2923401::numeric),
        ('JPY', 0.62846581::numeric, 0.64116209::numeric),
        ('CAD', 72.27608593::numeric, 73.73620887::numeric),
        ('AUD', 71.1398358::numeric, 72.5770042::numeric)
    ) AS c(code, buying, selling)
ON CONFLICT DO NOTHING;
