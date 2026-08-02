create table item (
    id bigint generated always as identity primary key,
    name text not null unique,
    basis_amount bigint not null check (basis_amount > 0),
    basis_unit text not null
);

create table item_amount (
    id bigint generated always as identity primary key,
    item_id bigint not null references item (id) on delete cascade,
    metric_id bigint not null references metric (id),
    amount bigint not null check (amount > 0),
    unique (item_id, metric_id)
);

create table serving (
    id bigint generated always as identity primary key,
    item_id bigint not null references item (id) on delete cascade,
    name text not null,
    quantity bigint not null check (quantity > 0),
    unique (item_id, name)
);
