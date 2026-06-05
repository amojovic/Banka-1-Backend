INSERT INTO zaposlen_permissions (zaposlen_id, permission)
SELECT e.id, 'OTC_TRADE'
FROM employees e
WHERE e.role = 'AGENT'
ON CONFLICT DO NOTHING;
