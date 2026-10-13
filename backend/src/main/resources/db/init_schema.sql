-- ============================================================
-- IntelliTutor 数据库初始化脚本 (PostgreSQL)
-- 目标数据库: intellitutor
-- ============================================================
-- 如果需要先创建数据库（在 psql 中以超级用户执行）：
-- CREATE DATABASE intellitutor OWNER postgres ENCODING 'UTF8';
-- ======================== 扩展 ========================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
-- ======================== t_user ========================
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
    -- 业务约束：email 和 phone_number 至少有一个非空
    CONSTRAINT chk_user_contact CHECK (
        email IS NOT NULL
        OR phone_number IS NOT NULL
    )
);
-- 常用查询索引
CREATE INDEX IF NOT EXISTS idx_user_email ON t_user (email)
WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_phone_number ON t_user (phone_number)
WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_created_at ON t_user (created_at);
COMMENT ON TABLE t_user IS '用户主表';
COMMENT ON COLUMN t_user.id IS '主键 UUID';
COMMENT ON COLUMN t_user.email IS '注册邮箱，与 phone_number 至少有一个';
COMMENT ON COLUMN t_user.phone_number IS '注册手机号，与 email 至少有一个';
COMMENT ON COLUMN t_user.password IS 'BCrypt 哈希密码';
COMMENT ON COLUMN t_user.nickname IS '用户昵称';
COMMENT ON COLUMN t_user.avatar IS '头像 URL，可空';
COMMENT ON COLUMN t_user.solved_problems IS '已解题数';
COMMENT ON COLUMN t_user.study_days IS '累计学习天数';
COMMENT ON COLUMN t_user.mastered_knowledge_points IS '已掌握知识点数';
COMMENT ON COLUMN t_user.weekly_study_questions IS '本周刷题数';
COMMENT ON COLUMN t_user.weekly_mastered_knowledge_points IS '本周新掌握知识点';
COMMENT ON COLUMN t_user.accuracy IS '正确率 0.0000~1.0000';
COMMENT ON COLUMN t_user.roles IS '角色列表 逗号分隔 默认 STUDENT';
COMMENT ON COLUMN t_user.deleted IS '逻辑删除 0=正常 1=已删除';