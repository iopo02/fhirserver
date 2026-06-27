-- V2__create_users_table.sql
-- Migration: Create users table and user_roles table for authentication/authorization

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    active BOOLEAN NOT NULL DEFAULT true,
    locked BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50),
    updated_by VARCHAR(50)
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active_locked ON users(active, locked);

-- User roles junction table (many-to-many)
CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_role ON user_roles(role);

-- Insert default admin user (password: admin123 -> BCrypt hashed)
-- Generated with: bcrypt("admin123") = $2a$10$7ZP1k92DCqsKjZJfFXMH/.qLGCFZEzJpCXJ7BVlKjqHmfr5W4mzNS
INSERT INTO users (username, email, password, first_name, last_name, active, locked, created_by, updated_by)
VALUES ('admin', 'admin@fhirserver.com', '$2a$10$7ZP1k92DCqsKjZJfFXMH/.qLGCFZEzJpCXJ7BVlKjqHmfr5W4mzNS', 'Admin', 'User', true, false, 'SYSTEM', 'SYSTEM')
ON CONFLICT (username) DO NOTHING;

-- Add admin role to default admin user
INSERT INTO user_roles (user_id, role)
SELECT id, 'ADMIN' FROM users WHERE username = 'admin'
ON CONFLICT DO NOTHING;

-- Insert default doctor user (password: doctor123 -> BCrypt hashed)
-- Generated with: bcrypt("doctor123") = $2a$10$Qj/5M5kKzN2d.9KK/d5Duu3JG.4KxZF3JxZqVJvOKD9Iyk6BXJJKm
INSERT INTO users (username, email, password, first_name, last_name, active, locked, created_by, updated_by)
VALUES ('doctor', 'doctor@fhirserver.com', '$2a$10$Qj/5M5kKzN2d.9KK/d5Duu3JG.4KxZF3JxZqVJvOKD9Iyk6BXJJKm', 'Doctor', 'User', true, false, 'SYSTEM', 'SYSTEM')
ON CONFLICT (username) DO NOTHING;

-- Add medico role to default doctor user
INSERT INTO user_roles (user_id, role)
SELECT id, 'MEDICO' FROM users WHERE username = 'doctor'
ON CONFLICT DO NOTHING;
