CREATE TABLE shoe_photo (
    id           VARCHAR(40)   PRIMARY KEY,
    shoe_id      VARCHAR(40)   NOT NULL REFERENCES shoe (id) ON DELETE CASCADE,
    image        BYTEA         NOT NULL,
    content_type VARCHAR(100)  NOT NULL,
    taken_on     DATE          NOT NULL,
    km           INTEGER       NOT NULL,
    notes        TEXT          NOT NULL DEFAULT '',
    created      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_shoe_photo_shoe ON shoe_photo (shoe_id);
