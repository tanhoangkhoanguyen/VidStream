-- Datasets workers take jobs from: active, with a member who has a Box token. A cleared token or a
-- lost member drops one out; a sign-in brings it back. Its jobs stay PENDING meanwhile.
CREATE VIEW workable_datasets AS
SELECT d.id, d.name FROM datasets d WHERE d.active AND EXISTS (
  SELECT 1 FROM dataset_members m JOIN users u ON u.id = m.user_id
  WHERE m.dataset_id = d.id AND u.refresh_token_enc IS NOT NULL);
