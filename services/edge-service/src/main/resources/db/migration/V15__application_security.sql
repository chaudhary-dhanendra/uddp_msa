CREATE TABLE app_user (user_id uuid PRIMARY KEY, username text NOT NULL UNIQUE, display_name text NOT NULL, password_hash text NOT NULL, enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app_role (role_code text PRIMARY KEY);
CREATE TABLE app_permission (permission_code text PRIMARY KEY);
CREATE TABLE app_user_role (user_id uuid NOT NULL REFERENCES app_user(user_id), role_code text NOT NULL REFERENCES app_role(role_code), PRIMARY KEY(user_id, role_code));
CREATE TABLE app_role_permission (role_code text NOT NULL REFERENCES app_role(role_code), permission_code text NOT NULL REFERENCES app_permission(permission_code), PRIMARY KEY(role_code, permission_code));
CREATE TABLE user_state_scope (user_id uuid NOT NULL REFERENCES app_user(user_id), state_code text NOT NULL, PRIMARY KEY(user_id, state_code));
CREATE TABLE app_audit_event (event_id uuid PRIMARY KEY, occurred_at timestamptz NOT NULL DEFAULT now(), actor_user_id uuid REFERENCES app_user(user_id), action text NOT NULL, resource_type text, resource_id text, state_code text, result text NOT NULL, metadata jsonb NOT NULL DEFAULT '{}'::jsonb);
