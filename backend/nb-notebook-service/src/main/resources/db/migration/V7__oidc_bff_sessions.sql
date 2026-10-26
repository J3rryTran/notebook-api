ALTER TABLE notebook_auth_sessions MODIFY credential_enc VARBINARY(32768) NULL;

CREATE TABLE IF NOT EXISTS notebook_oidc_pending_logins (
  state VARCHAR(64) NOT NULL,
  code_verifier VARCHAR(128) NOT NULL,
  nonce VARCHAR(64) NOT NULL,
  redirect_path VARCHAR(1024) NOT NULL,
  redirect_uri VARCHAR(1024) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (state),
  KEY idx_nopl_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
