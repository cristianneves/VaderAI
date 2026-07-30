package ai.vader.server.preferences;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The row {@code handle_new_user} creates on sign-up. We never insert or delete
 * one — Supabase Auth owns its lifecycle — so this exists only to give the
 * repository an aggregate type to map.
 */
@Table("profiles")
record Profile(@Id UUID id, String email, Instant createdAt, String language) {}
