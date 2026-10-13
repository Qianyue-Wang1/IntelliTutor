CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS t_user (
    id VARCHAR(36) PRIMARY KEY DEFAULT uuid_generate_v4()::VARCHAR(36),
    email VARCHAR(128) UNIQUE,
    phone_number VARCHAR(20) UNIQUE,
    password VARCHAR(128) NOT NULL,
    nickname VARCHAR(64),
    avatar TEXT,
    solved_problems INTEGER NOT NULL DEFAULT 0,
    study_days INTEGER NOT NULL DEFAULT 0,
    mastered_knowledge_points INTEGER NOT NULL DEFAULT 0,
    weekly_study_questions INTEGER NOT NULL DEFAULT 0,
    weekly_mastered_knowledge_points INTEGER NOT NULL DEFAULT 0,
    accuracy NUMERIC(5, 4) NOT NULL DEFAULT 0.0000,
    roles VARCHAR(128) NOT NULL DEFAULT 'STUDENT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT chk_user_contact CHECK (email IS NOT NULL OR phone_number IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_user_email ON t_user (email) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_phone_number ON t_user (phone_number) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_created_at ON t_user (created_at);
