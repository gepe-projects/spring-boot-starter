CREATE TABLE auth.roles (
    name VARCHAR(20) NOT NULL,
    CONSTRAINT pk_roles PRIMARY KEY (name)
);

INSERT INTO auth.roles (name) VALUES ('ADMIN'), ('USER');

CREATE TABLE auth.user_roles (
    user_id UUID         NOT NULL,
    role    VARCHAR(20)  NOT NULL,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role) REFERENCES auth.roles (name) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_user
    ON auth.user_roles (user_id);
