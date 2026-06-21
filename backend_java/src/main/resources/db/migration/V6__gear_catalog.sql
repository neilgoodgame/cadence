CREATE TABLE shoe_model (
    id            VARCHAR(40)  PRIMARY KEY,
    manufacturer  VARCHAR(150) NOT NULL,
    model         VARCHAR(150) NOT NULL
);

CREATE TABLE shoe_model_version (
    id              VARCHAR(40)  PRIMARY KEY,
    shoe_model_id   VARCHAR(40)  NOT NULL REFERENCES shoe_model (id) ON DELETE RESTRICT,
    version         VARCHAR(50)  NOT NULL
);

CREATE INDEX idx_shoe_model_version_model ON shoe_model_version (shoe_model_id);
