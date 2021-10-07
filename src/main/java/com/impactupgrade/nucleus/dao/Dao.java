package com.impactupgrade.nucleus.dao;

import java.util.Optional;

public interface Dao<I, E> {

    E create(E e, Optional<Long> ttl);

    Optional<E> get(I id);

    E update(E e);

    Optional<E> delete(I id);

}
