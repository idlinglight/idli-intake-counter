create table metric (
    id bigint generated always as identity primary key,
    name text not null unique,
    canonical_unit text not null
);

create table entry (
    id bigint generated always as identity primary key,
    metric_id bigint not null references metric (id),
    amount bigint not null check (amount > 0),
    logged_at timestamptz not null
);

create index entry_logged_at_idx on entry (logged_at);

insert into metric (name, canonical_unit) values ('water', 'mL');
