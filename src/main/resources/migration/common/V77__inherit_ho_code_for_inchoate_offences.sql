-- MOP-244
-- Inchoate offences (attempt, conspiracy, incitement, aiding) with no Home Office code take the code of their
-- substantive parent offence, as the Home Office Counting Rules intend.
-- Offences classified in their own right (e.g. attempted murder, attempted rape) are mapped by the stats team,
-- so their category is not null and is left untouched here.
-- Encouragement ('E') offences are maintained from their parent by the trigger added in V76.
WITH updated AS (
    UPDATE offence c
        SET category = p.category,
            sub_category = p.sub_category,
            last_updated_date = NOW()
        FROM offence p
        WHERE c.parent_offence_id = p.id
            AND c.code <> p.code || 'E'
            AND c.category IS NULL
            AND c.sub_category IS NULL
            AND p.category IS NOT NULL
        RETURNING c.code)
INSERT
INTO offence_to_sync_with_nomis (offence_code, nomis_sync_type, created_date)
SELECT u.code, 'HO_CODE_UPDATE', NOW()
FROM updated u
WHERE NOT EXISTS (SELECT 1
                  FROM offence_to_sync_with_nomis s
                  WHERE s.offence_code = u.code
                    AND s.nomis_sync_type = 'HO_CODE_UPDATE');
