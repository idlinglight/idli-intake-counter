-- Grouped snapshot (ADR-0007 amendment): one serving-log action fans out to
-- N entries sharing a group id and a snapshotted human label. Ad-hoc entries
-- carry neither.
alter table entry
    add column group_id uuid,
    add column label text,
    add constraint entry_group_label_together
        check ((group_id is null) = (label is null));

create index entry_group_id_idx on entry (group_id) where group_id is not null;
