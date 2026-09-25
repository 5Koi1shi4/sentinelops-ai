-- 运行时应用只允许删除已经过期的 Webhook nonce。
-- Flyway 迁移用户仍是表所有者，可管理 RLS 策略之外的维护操作。
-- RLS 策略定义行过滤，不授予表级 DML；对象权限仍由授权脚本控制。
-- 省略 TO 子句使迁移不依赖运行时角色已存在。
ALTER TABLE public.webhook_replay_nonce ENABLE ROW LEVEL SECURITY;

CREATE POLICY webhook_replay_nonce_app_select
  ON public.webhook_replay_nonce
  FOR SELECT
  USING (true);

CREATE POLICY webhook_replay_nonce_app_insert
  ON public.webhook_replay_nonce
  FOR INSERT
  WITH CHECK (true);

CREATE POLICY webhook_replay_nonce_app_delete_expired
  ON public.webhook_replay_nonce
  FOR DELETE
  USING (expires_at <= statement_timestamp());
