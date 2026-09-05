# Pre-production checklist

- [ ] `mvn clean verify` verde
- [ ] Migraciones Flyway verdes
- [ ] Conexion PostgreSQL validada
- [ ] Login y refresh JWT validados
- [ ] Autorizacion por permisos revisada
- [ ] Secrets externos configurados
- [ ] CORS limitado al frontend real
- [ ] Swagger configurado o deshabilitado
- [ ] `/actuator/health` y `/actuator/info` revisados
- [ ] Logs sin secretos y con correlation id
- [ ] Tests de integracion con PostgreSQL ejecutados
- [ ] Docker build validado
- [ ] Perfil `prod` validado
- [ ] Sin `ddl-auto=create` ni `create-drop`
- [ ] Backups PostgreSQL definidos
- [ ] HTTPS delante de la app
- [ ] URL API del frontend configurable
