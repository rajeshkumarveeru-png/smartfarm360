package com.smartfarm360.platform.rls;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Sets {@code app.tenant_id} (and custom planning) on every connection as it is borrowed (ADR 0011). The value is overwritten at each borrow,
 * so a pooled connection never carries one request's scope into another.
 */
@Configuration
public class RlsDataSourceConfig {
    @Bean
    static BeanPostProcessor rlsDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds && !(bean instanceof ScopedDataSource) ? new ScopedDataSource(ds) : bean;
            }
        };
    }

    static final class ScopedDataSource extends DelegatingDataSource {
        ScopedDataSource(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws SQLException { return scoped(super.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException { return scoped(super.getConnection(username, password)); }

        private static Connection scoped(Connection c) throws SQLException {
            // Custom plans too: a generic plan cannot use the policy's tenant or a report's optional filters, and turned the
            // income statement from 100 ms into a second (ADR 0011). Planning each statement costs about a millisecond.
            try (PreparedStatement s = c.prepareStatement("select set_config('app.tenant_id', ?, false), set_config('plan_cache_mode', 'force_custom_plan', false)")) {
                s.setString(1, RlsScope.current());
                s.execute();
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            return c;
        }
    }
}
