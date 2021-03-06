package org.icij.kaxxa.sql.concurrent;

import org.icij.kaxxa.sql.concurrent.function.CheckedConsumer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

public class MySQLConcurrentMap<K, V> extends SQLConcurrentMap<K, V> {

	private final String table;
	private final SQLCodec<V> codec;

	public MySQLConcurrentMap(final DataSource dataSource, final SQLCodec<V> codec, final String table) {
		super(dataSource);
		this.table = table;
		this.codec = codec;
	}

	private int executeInsert(final Connection c, final K key, final V value) throws SQLException {
		final Map<String, Object> values = codec.encodeValue(value);
		values.putAll(codec.encodeKey(key));

		try (final PreparedStatement q = c.prepareStatement("INSERT " + table + " SET " +
				String.join(",", values.keySet().stream().map(k -> k + " = ?").toArray(String[]::new)) + ";")) {

			int i = 1;
			for (String k : values.keySet()) {
				q.setObject(i++, values.get(k));
			}

			return q.executeUpdate();
		}
	}

	private int executeInsertOrUpdate(final Connection c, final K key, final V value) throws SQLException {
		final Map<String, Object> values = codec.encodeValue(value);
		values.putAll(codec.encodeKey(key));

		final Set<String> keySet = values.keySet();
		final String placeholders = String.join(", ", keySet.stream().map(k -> k + " = ?")
				.toArray(String[]::new));

		try (final PreparedStatement q = c.prepareStatement("INSERT " + table + " SET " + placeholders +
				" ON DUPLICATE KEY UPDATE " + placeholders + ";")) {
			int i = 1;
			final int l = keySet.size();

			for (String k : keySet) {
				q.setObject(i, values.get(k));
				q.setObject(l + i, values.get(k));
				i++;
			}

			return q.executeUpdate();
		}
	}

	private int executeUpdate(final Connection c, final K key, final V value) throws SQLException {
		final Map<String, Object> values = codec.encodeValue(value);
		final Set<String> valuesKeySet = values.keySet();

		final Map<String, Object> keys = codec.encodeKey(key);
		final Set<String> keysKeySet = keys.keySet();

		try (final PreparedStatement q = c.prepareStatement("UPDATE " + table + " SET " +
				String.join(",", valuesKeySet.stream().map(k -> k + " = ?").toArray(String[]::new)) +
				" WHERE " +
				String.join(" AND ", keysKeySet.stream().map(k -> k + " = ?").toArray(String[]::new)) + ";")) {
			int i = 1;

			for (String k: valuesKeySet) {
				q.setObject(i++, values.get(k));
			}

			for (String k: keysKeySet) {
				q.setObject(i++, keys.get(k));
			}

			return q.executeUpdate();
		}
	}

	private V executeSelectForUpdate(final Connection c, final Object key) throws SQLException {
		final Map<String, Object> keys = codec.encodeKey(key);
		final Set<String> keySet = keys.keySet();

		try (final PreparedStatement q = c.prepareStatement("SELECT * FROM " + table + " WHERE " +
				String.join(" AND ", keySet.stream().map(k -> k + " = ?").toArray(String[]::new)) +
				" LIMIT 1 FOR UPDATE;")) {
			int i = 1;
			for (String k: keySet) {
				q.setObject(i++, keys.get(k));
			}

			try (final ResultSet rs = q.executeQuery()) {
				if (!rs.next()) {
					return null;
				}

				return codec.decodeValue(rs);
			}
		}
	}

	private V executeSelect(final Connection c, final Object key) throws SQLException {
		final Map<String, Object> keys = codec.encodeKey(key);
		final Set<String> keySet = keys.keySet();

		try (final PreparedStatement q = c.prepareStatement("SELECT * FROM " + table + " WHERE " +
				String.join(" AND ", keySet.stream().map(k -> k + " = ?").toArray(String[]::new)) + ";")) {
			int i = 1;
			for (String k: keySet) {
				q.setObject(i++, keys.get(k));
			}

			try (final ResultSet rs = q.executeQuery()) {
				if (!rs.next()) {
					return null;
				}

				return codec.decodeValue(rs);
			}
		}
	}

	@Override
	public boolean replace(final K key, final V oldValue, final V newValue) {
		return dataSource.withConnection(c -> {
			final int result;
			c.setAutoCommit(false);

			try {
				if (!oldValue.equals(executeSelectForUpdate(c, key))) {
					c.rollback();
					return false;
				}

				result = executeUpdate(c, key, newValue);
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			c.commit();
			return result > 0;
		});
	}

	@Override
	public V replace(final K key, final V value) {
		return dataSource.withConnection(c -> {
			final V oldValue;
			c.setAutoCommit(false);

			try {
				oldValue = executeSelectForUpdate(c, key);
				executeUpdate(c, key, value);
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			c.commit();
			return oldValue;
		});
	}

	@Override
	public void clear() {
		dataSource.withStatement("DELETE FROM " + table + ";",
				(CheckedConsumer<PreparedStatement>) PreparedStatement::executeUpdate);
	}

	@Override
	public boolean remove(final Object key, final Object value) {
		return dataSource.withConnection(c -> {
			final int result;
			c.setAutoCommit(false);

			try {
				if (!value.equals(executeSelectForUpdate(c, key))) {
					c.rollback();
					return false;
				}
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			final Map<String, Object> values = codec.encodeKey(key);
			values.putAll(codec.encodeValue(value));

			final Set<String> keySet = values.keySet();

			try (final PreparedStatement q = c.prepareStatement("DELETE FROM " + table + " WHERE " +
					String.join(" AND ", keySet.stream().map(k -> k + " = ?").toArray(String[]::new)) + ";")) {
				int i = 1;
				for (String k : keySet) {
					q.setObject(i++, values.get(k));
				}

				result = q.executeUpdate();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			c.commit();
			return result > 0;
		});
	}

	@Override
	public V remove(final Object key) {
		return dataSource.withConnection(c -> {
			final V oldValue;
			c.setAutoCommit(false);

			try {
				oldValue = executeSelectForUpdate(c, key);
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			final Map<String, Object> keys = codec.encodeKey(key);
			final Set<String> keySet = keys.keySet();

			try (final PreparedStatement q = c.prepareStatement("DELETE FROM " + table + " WHERE " +
					String.join(" AND ", keySet.stream().map(k -> k + " = ?").toArray(String[]::new)) + ";")) {
				int i = 1;
				for (String k : keySet) {
					q.setObject(i++, keys.get(k));
				}

				q.executeUpdate();
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			c.commit();
			return oldValue;
		});
	}

	@Override
	public V put(final K key, final V value) {
		return dataSource.withConnection(c -> {
			final V oldValue;
			c.setAutoCommit(false);

			try {
				oldValue = executeSelectForUpdate(c, key);
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			try {
				if (null == oldValue) {
					executeUpdate(c, key, value);
				} else {

					// There's a race condition here, like with #putIfAbsent. See below.
					// TODO: use a lock.
					executeInsert(c, key, value);
				}
			} catch (SQLException e) {
				c.rollback();
				throw e;
			}

			c.commit();
			return oldValue;
		});
	}

	@Override
	public V putIfAbsent(final K key, final V value) {
		return dataSource.withConnection(c -> {
			final V oldValue = executeSelect(c, key);

			if (null != oldValue) {
				return oldValue;
			}

			// There's a race condition here. An exception might be thrown if a record with the same keys is inserted
			// between the call to #get(...) and this point.
			// TODO: use a lock.
			executeInsert(c, key, value);
			return null;
		});
	}

	@Override
	public boolean fastPut(final K key, final V value) {
		return dataSource.withConnection(c -> executeInsertOrUpdate(c, key, value) > 0);
	}

	@Override
	public int size() {
		return dataSource.withStatement("SELECT COUNT(*) FROM " + table + ";", q -> {
			try (final ResultSet rs = q.executeQuery()) {
				rs.next();
				return rs.getInt(0);
			}
		});
	}

	@Override
	public void putAll(final Map<? extends K, ? extends V> m) {
		dataSource.withConnection(c -> {
			for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
				executeInsertOrUpdate(c, e.getKey(), e.getValue());
			}
		});
	}

	@Override
	public boolean containsKey(final Object key) {
		final Map<String, Object> keys = codec.encodeKey(key);
		final Set<String> keySet = keys.keySet();

		return dataSource.withStatement("SELECT EXISTS(SELECT * FROM " + table + " WHERE " +
				String.join(" AND ", keySet.stream().map(k -> k + " = ?").toArray(String[]::new)) + ";", q -> {
			int i = 1;
			for (String k: keySet) {
				q.setObject(i++, keys.get(k));
			}

			try (final ResultSet rs = q.executeQuery()) {
				rs.next();
				return rs.getBoolean(0);
			}
		});
	}

	@Override
	public boolean containsValue(final Object value) {
		final Map<String, Object> values = codec.encodeValue(value);
		final Set<String> keySet = values.keySet();

		return dataSource.withStatement("SELECT EXISTS(SELECT * FROM " + table + " WHERE " +
				String.join(" AND ", keySet.stream().map((k)-> k + " = ?").toArray(String[]::new)) + ");", q -> {
			int i = 1;
			for (String k: keySet) {
				q.setObject(i++, values.get(k));
			}

			try (final ResultSet rs = q.executeQuery()) {
				rs.next();
				return rs.getBoolean(0);
			}
		});
	}

	@Override
	public boolean isEmpty() {
		return dataSource.withStatement("SELECT EXISTS(SELECT * FROM " + table + ");", q -> {
			final ResultSet rs = q.executeQuery();

			rs.next();
			return rs.getBoolean(0);
		});
	}

	@Override
	public V get(final Object key) {
		return dataSource.withConnection(c -> {
			return executeSelect(c, key);
		});
	}
}
