# --- !Ups

DROP MATERIALIZED VIEW home_projects;

UPDATE project_version_tags
SET name = CASE
               WHEN name = 'Sponge' THEN 'spongeapi'
               WHEN name = 'SpongeForge' THEN 'spongeforge'
               WHEN name = 'SpongeVanilla' THEN 'spongevanilla'
               WHEN name = 'SpongeCommon' THEN 'sponge'
               WHEN name = 'Lantern' THEN 'lantern'
               WHEN name = 'Forge' THEN 'forge'
               ELSE name END;

UPDATE project_version_tags
SET name = 'stability',
    data = 'alpha'
WHERE name = 'Unstable';

CREATE FUNCTION platform_version_from_tag(name TEXT, data TEXT) RETURNS TEXT
    LANGUAGE plpgsql
    IMMUTABLE RETURNS NULL ON NULL INPUT AS
$$
BEGIN
    CASE $1
        WHEN 'spongeapi' THEN RETURN substring($2 FROM
                                               '^\[?(\d+)\.\d+(?:\.\d+)?(?:-SNAPSHOT)?(?:-[a-z0-9]{7,9})?(?:,(?:\d+\.\d+(?:\.\d+)?)?\))?$');;
        WHEN 'spongeforge' THEN RETURN substring($2 FROM
                                                 '^\d+\.\d+\.\d+-\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$');;
        WHEN 'spongevanilla' THEN RETURN substring($2 FROM
                                                   '^\d+\.\d+\.\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$');;
        WHEN 'forge' THEN RETURN substring($2 FROM '^\d+\.(\d+)\.\d+(?:\.\d+)?$');;
        WHEN 'lantern' THEN RETURN NULL;; --TODO Change this once Lantern changes to SpongeVanilla's format
        ELSE
        END CASE;;

    RETURN NULL;;
END;;
$$;

ALTER TABLE project_version_tags
    ADD COLUMN platform_version TEXT GENERATED ALWAYS AS (platform_version_from_tag(NAME, DATA)) STORED;

CREATE FUNCTION stability_from_channel(name TEXT) RETURNS TEXT
    LANGUAGE plpgsql
    IMMUTABLE RETURNS NULL ON NULL INPUT AS
$$
BEGIN
    CASE lower($1)
        WHEN 'beta' THEN RETURN 'beta';;
        WHEN 'alpha' THEN RETURN 'alpha';;
        WHEN 'bleeding' THEN RETURN 'bleeding';;
        WHEN 'snapshot' THEN RETURN 'bleeding';;
        WHEN 'snapshots' THEN RETURN 'bleeding';;
        WHEN 'prerelease' THEN RETURN 'beta';;
        WHEN 'pre' THEN RETURN 'beta';;
        WHEN 'outofdate' THEN RETURN 'unsupported';;
        WHEN 'old' THEN RETURN 'unsupported';;
        WHEN 'workinprogress' THEN RETURN 'beta';;
        WHEN 'devbuild' THEN RETURN 'alpha';;
        WHEN 'development' THEN RETURN 'alpha';;
        WHEN 'spongebleeding' THEN RETURN 'bleeding';;
        ELSE
        END CASE;;

    RETURN 'stable';;
END;;
$$;

CREATE FUNCTION color_from_stability(name TEXT) RETURNS INT
    LANGUAGE plpgsql
    IMMUTABLE RETURNS NULL ON NULL INPUT AS
$$
BEGIN
    CASE name
        --TODO: Create better colors here
        WHEN 'stable' THEN RETURN 17;;
        WHEN 'beta' THEN RETURN 20;;
        WHEN 'alpha' THEN RETURN 22;;
        WHEN 'unsupported' THEN RETURN 9;;
        WHEN 'bleeding' THEN RETURN 23;;
        ELSE
        END CASE;;

    RETURN 17;;
END;;
$$;

INSERT INTO project_version_tags (version_id, name, data, color)
SELECT pv.id,
       'stability',
       stability_from_channel(pc.name),
       color_from_stability(stability_from_channel(pc.name))
FROM project_versions pv
         JOIN project_channels pc ON pv.channel_id = pc.id
WHERE NOT EXISTS(SELECT * FROM project_version_tags pvt WHERE pvt.version_id = pv.id AND pvt.name = 'stability');

DROP FUNCTION stability_from_channel;
DROP FUNCTION color_from_stability;

INSERT INTO project_version_tags (version_id, name, data, color)
SELECT pv.id, 'channel', pc.name, pc.color + 9
FROM project_versions pv
         JOIN project_channels pc ON pv.channel_id = pc.id
WHERE NOT EXISTS(SELECT stability_channels.name
                 FROM (VALUES --Stability
                              ('Release'),
                              ('Beta'),
                              ('Alpha'),
                              ('Bleeding'),
                              ('Snapshot%'),
                              ('Prerelease'),
                              ('Pre'),
                              ('OutOfDate'),
                              ('Stable'),
                              ('Unstable'),
                              ('ReleaseAPI_'),
                              ('Old'),
                              ('WorkInProgress'),
                              ('DevBuild'),
                              ('Dev'),
                              ('Development'),
                              --Platform
                              ('Forge'),
                              ('Sponge'),
                              ('Sponge_'),
                              ('API_'),
                              ('SpongeAPI_'),
                              ('SpongeBleeding')
                      ) AS stability_channels(name)
                 WHERE pc.name ILIKE stability_channels.name);

ALTER TABLE project_versions
    DROP COLUMN channel_id;

DROP TABLE project_channels;

ALTER TABLE projects
    DROP COLUMN recommended_version_id;

CREATE MATERIALIZED VIEW home_projects AS
    WITH tags AS (
        SELECT sq.project_id, sq.version_string, sq.tag_name, sq.tag_version, sq.tag_color
        FROM (SELECT pv.project_id,
                     pv.version_string,
                     pvt.name                                                                            AS tag_name,
                     pvt.data                                                                            AS tag_version,
                     pvt.platform_version,
                     pvt.color                                                                           AS tag_color,
                     row_number()
                     OVER (PARTITION BY pv.project_id, pvt.platform_version ORDER BY pv.created_at DESC) AS row_num
              FROM project_versions pv
                       JOIN project_version_tags pvt ON pv.id = pvt.version_id
              WHERE pv.visibility = 1
                AND pvt.platform_version IS NOT NULL) sq
        WHERE sq.row_num = 1
        ORDER BY sq.platform_version DESC)
    SELECT p.id,
           p.owner_name                      AS owner_name,
           array_agg(DISTINCT pm.user_id)    AS project_members,
           p.slug,
           p.visibility,
           coalesce(pva.views, 0)            AS views,
           coalesce(pda.downloads, 0)        AS downloads,
           coalesce(pvr.recent_views, 0)     AS recent_views,
           coalesce(pdr.recent_downloads, 0) AS recent_downloads,
           coalesce(ps.stars, 0)             AS stars,
           coalesce(pw.watchers, 0)          AS watchers,
           p.category,
           p.description,
           p.name,
           p.plugin_id,
           p.created_at,
           max(lv.created_at)                AS last_updated,
           to_jsonb(
                   ARRAY(SELECT jsonb_build_object('version_string', tags.version_string, 'tag_name',
                                                   tags.tag_name,
                                                   'tag_version', tags.tag_version, 'tag_color',
                                                   tags.tag_color)
                         FROM tags
                         WHERE tags.project_id = p.id
                         LIMIT 5))           AS promoted_versions,
           setweight(to_tsvector('english', p.name) ||
                     to_tsvector('english', regexp_replace(p.name, '([a-z])([A-Z]+)', '\1_\2', 'g')) ||
                     to_tsvector('english', p.plugin_id), 'A') ||
           setweight(to_tsvector('english', p.description), 'B') ||
           setweight(to_tsvector('english', array_to_string(p.keywords, ' ')), 'C') ||
           setweight(to_tsvector('english', p.owner_name) ||
                     to_tsvector('english', regexp_replace(p.owner_name, '([a-z])([A-Z]+)', '\1_\2', 'g')),
                     'D')                    AS search_words
    FROM projects p
             LEFT JOIN project_versions lv ON p.id = lv.project_id
             JOIN project_members_all pm ON p.id = pm.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS stars
                        FROM projects p
                                 LEFT JOIN project_stars ps ON p.id = ps.project_id
                        GROUP BY p.id) ps ON p.id = ps.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS watchers
                        FROM projects p
                                 LEFT JOIN project_watchers pw ON p.id = pw.project_id
                        GROUP BY p.id) pw ON p.id = pw.id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS views FROM project_views pv GROUP BY pv.project_id) pva
                       ON p.id = pva.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS downloads
                        FROM project_versions_downloads pv
                        GROUP BY pv.project_id) pda ON p.id = pda.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS recent_views
                        FROM project_views pv
                        WHERE pv.day BETWEEN CURRENT_DATE - INTERVAL '30 days' AND CURRENT_DATE
                        GROUP BY pv.project_id) pvr
                       ON p.id = pvr.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS recent_downloads
                        FROM project_versions_downloads pv
                        WHERE pv.day BETWEEN CURRENT_DATE - INTERVAL '30 days' AND CURRENT_DATE
                        GROUP BY pv.project_id) pdr ON p.id = pdr.project_id
    GROUP BY p.id, ps.stars, pw.watchers, pva.views, pda.downloads, pvr.recent_views, pdr.recent_downloads;

# --- !Downs

DROP MATERIALIZED VIEW home_projects;

ALTER TABLE projects
    ADD COLUMN recommended_version_id BIGINT REFERENCES project_versions ON DELETE SET NULL;

CREATE INDEX projects_recommended_version_id ON projects (recommended_version_id);

CREATE TABLE project_channels
(
    id              BIGSERIAL             NOT NULL PRIMARY KEY,
    created_at      TIMESTAMPTZ           NOT NULL,
    name            VARCHAR(255)          NOT NULL,
    color           INTEGER               NOT NULL,
    project_id      BIGINT                NOT NULL REFERENCES projects ON DELETE CASCADE,
    is_non_reviewed BOOLEAN DEFAULT FALSE NOT NULL,
    UNIQUE (project_id, color),
    UNIQUE (project_id, name)
);

ALTER TABLE project_versions
    ADD COLUMN channel_id BIGINT REFERENCES project_channels;

INSERT INTO project_channels (created_at, name, color, project_id, is_non_reviewed)
SELECT p.created_at, 'Release', 8, p.id, FALSE
FROM projects p
UNION ALL
SELECT p.created_at, 'Beta', 11, p.id, TRUE
FROM projects p
UNION ALL
SELECT p.created_at, 'Alpha', 13, p.id, TRUE
FROM projects p
UNION ALL
SELECT p.created_at, 'Unsupported', 2, p.id, FALSE
FROM projects p
UNION ALL
SELECT p.created_at, 'Bleeding', 14, p.id, TRUE
FROM projects p;

INSERT INTO project_channels (created_at, name, color, project_id)
SELECT DISTINCT ON (p.id, pvt.data) p.created_at, pvt.data, pvt.color - 9, p.id
FROM project_version_tags pvt
         JOIN project_versions pv ON pvt.version_id = pv.id
         JOIN projects p ON pv.project_id = p.id
WHERE pvt.name = 'channel';

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_version_tags pvt
         JOIN project_channels pc ON pvt.data = pc.name
WHERE pvt.name = 'channel'
  AND pvt.version_id = pv.id;

DELETE
FROM project_version_tags
WHERE name = 'channel';

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_channels pc
WHERE pc.name = 'Release'
  AND pv.channel_id IS NULL
  AND EXISTS(SELECT *
             FROM project_version_tags pvt
             WHERE pvt.version_id = pv.id
               AND pvt.name = 'stability'
               AND pvt.data = 'stable');

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_channels pc
WHERE pc.name = 'Beta'
  AND pv.channel_id IS NULL
  AND EXISTS(SELECT *
             FROM project_version_tags pvt
             WHERE pvt.version_id = pv.id
               AND pvt.name = 'stability'
               AND pvt.data = 'beta');

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_channels pc
WHERE pc.name = 'Alpha'
  AND pv.channel_id IS NULL
  AND EXISTS(SELECT *
             FROM project_version_tags pvt
             WHERE pvt.version_id = pv.id
               AND pvt.name = 'stability'
               AND pvt.data = 'alpha');

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_channels pc
WHERE pc.name = 'Unsupported'
  AND pv.channel_id IS NULL
  AND EXISTS(SELECT *
             FROM project_version_tags pvt
             WHERE pvt.version_id = pv.id
               AND pvt.name = 'stability'
               AND pvt.data = 'unsupported');

UPDATE project_versions pv
SET channel_id = pc.id
FROM project_channels pc
WHERE pc.name = 'Bleeding'
  AND pv.channel_id IS NULL
  AND EXISTS(SELECT *
             FROM project_version_tags pvt
             WHERE pvt.version_id = pv.id
               AND pvt.name = 'stability'
               AND pvt.data = 'bleeding');

ALTER TABLE project_versions
    ALTER COLUMN channel_id SET NOT NULL;

ALTER TABLE project_version_tags
    DROP COLUMN platform_version;

DROP FUNCTION platform_version_from_tag(NAME TEXT, DATA TEXT);

UPDATE project_version_tags
SET name = CASE
               WHEN name = 'spongeapi' THEN 'Sponge'
               WHEN name = 'spongeforge' THEN 'SpongeForge'
               WHEN name = 'spongevanilla' THEN 'SpongeVanilla'
               WHEN name = 'sponge' THEN 'SpongeCommon'
               WHEN name = 'lantern' THEN 'Lantern'
               WHEN name = 'forge' THEN 'Forge'
               ELSE name END;

INSERT INTO project_version_tags (version_id, name, data, color)
SELECT pvt.version_id, 'Unstable', NULL, pvt.color
FROM project_version_tags pvt
WHERE pvt.name = 'stability'
  AND pvt.data = 'alpha';

DELETE
FROM project_version_tags
WHERE name = 'stability';

CREATE MATERIALIZED VIEW home_projects AS
    WITH tags AS (
        SELECT sq.project_id, sq.version_string, sq.tag_name, sq.tag_version, sq.tag_color
        FROM (SELECT pv.project_id,
                     pv.version_string,
                     pvt.name                                                                            AS tag_name,
                     pvt.data                                                                            AS tag_version,
                     pvt.platform_version,
                     pvt.color                                                                           AS tag_color,
                     row_number()
                     OVER (PARTITION BY pv.project_id, pvt.platform_version ORDER BY pv.created_at DESC) AS row_num
              FROM project_versions pv
                       JOIN (
                  SELECT pvti.version_id,
                         pvti.name,
                         pvti.data,
                         --TODO, use a STORED column in Postgres 12
                         CASE
                             WHEN pvti.name = 'Sponge'
                                 THEN substring(pvti.data FROM
                                                '^\[?(\d+)\.\d+(?:\.\d+)?(?:-SNAPSHOT)?(?:-[a-z0-9]{7,9})?(?:,(?:\d+\.\d+(?:\.\d+)?)?\))?$')
                             WHEN pvti.name = 'SpongeForge'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'SpongeVanilla'
                                 THEN substring(pvti.data FROM
                                                '^\d+\.\d+\.\d+-(\d+)\.\d+\.\d+(?:(?:-BETA-\d+)|(?:-RC\d+))?$')
                             WHEN pvti.name = 'Forge'
                                 THEN substring(pvti.data FROM '^\d+\.(\d+)\.\d+(?:\.\d+)?$')
                             WHEN pvti.name = 'Lantern'
                                 THEN NULL --TODO Change this once Lantern changes to SpongeVanilla's format
                             ELSE NULL
                             END AS platform_version,
                         pvti.color
                  FROM project_version_tags pvti
                  WHERE pvti.name IN ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                    AND pvti.data IS NOT NULL
              ) pvt ON pv.id = pvt.version_id
              WHERE pv.visibility = 1
                AND pvt.name IN
                    ('Sponge', 'SpongeForge', 'SpongeVanilla', 'Forge', 'Lantern')
                AND pvt.platform_version IS NOT NULL) sq
        WHERE sq.row_num = 1
        ORDER BY sq.platform_version DESC)
    SELECT p.id,
           p.owner_name                      AS owner_name,
           array_agg(DISTINCT pm.user_id)    AS project_members,
           p.slug,
           p.visibility,
           coalesce(pva.views, 0)            AS views,
           coalesce(pda.downloads, 0)        AS downloads,
           coalesce(pvr.recent_views, 0)     AS recent_views,
           coalesce(pdr.recent_downloads, 0) AS recent_downloads,
           coalesce(ps.stars, 0)             AS stars,
           coalesce(pw.watchers, 0)          AS watchers,
           p.category,
           p.description,
           p.name,
           p.plugin_id,
           p.created_at,
           max(lv.created_at)                AS last_updated,
           to_jsonb(
                   ARRAY(SELECT jsonb_build_object('version_string', tags.version_string, 'tag_name',
                                                   tags.tag_name,
                                                   'tag_version', tags.tag_version, 'tag_color',
                                                   tags.tag_color)
                         FROM tags
                         WHERE tags.project_id = p.id
                         LIMIT 5))           AS promoted_versions,
           setweight(to_tsvector('english', p.name) ||
                     to_tsvector('english', regexp_replace(p.name, '([a-z])([A-Z]+)', '\1_\2', 'g')) ||
                     to_tsvector('english', p.plugin_id), 'A') ||
           setweight(to_tsvector('english', p.description), 'B') ||
           setweight(to_tsvector('english', array_to_string(p.keywords, ' ')), 'C') ||
           setweight(to_tsvector('english', p.owner_name) ||
                     to_tsvector('english', regexp_replace(p.owner_name, '([a-z])([A-Z]+)', '\1_\2', 'g')),
                     'D')                    AS search_words
    FROM projects p
             LEFT JOIN project_versions lv ON p.id = lv.project_id
             JOIN project_members_all pm ON p.id = pm.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS stars
                        FROM projects p
                                 LEFT JOIN project_stars ps ON p.id = ps.project_id
                        GROUP BY p.id) ps ON p.id = ps.id
             LEFT JOIN (SELECT p.id, COUNT(*) AS watchers
                        FROM projects p
                                 LEFT JOIN project_watchers pw ON p.id = pw.project_id
                        GROUP BY p.id) pw ON p.id = pw.id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS views FROM project_views pv GROUP BY pv.project_id) pva
                       ON p.id = pva.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS downloads
                        FROM project_versions_downloads pv
                        GROUP BY pv.project_id) pda ON p.id = pda.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.views) AS recent_views
                        FROM project_views pv
                        WHERE pv.day BETWEEN CURRENT_DATE - INTERVAL '30 days' AND CURRENT_DATE
                        GROUP BY pv.project_id) pvr
                       ON p.id = pvr.project_id
             LEFT JOIN (SELECT pv.project_id, sum(pv.downloads) AS recent_downloads
                        FROM project_versions_downloads pv
                        WHERE pv.day BETWEEN CURRENT_DATE - INTERVAL '30 days' AND CURRENT_DATE
                        GROUP BY pv.project_id) pdr ON p.id = pdr.project_id
    GROUP BY p.id, ps.stars, pw.watchers, pva.views, pda.downloads, pvr.recent_views, pdr.recent_downloads;
