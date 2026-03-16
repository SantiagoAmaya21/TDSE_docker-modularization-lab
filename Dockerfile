FROM eclipse-temurin:11-jre-alpine

WORKDIR /usrapp/bin

ENV PORT 35000

# Estos directorios se generan al ejecutar localmente:
#   mvn package
COPY target/classes /usrapp/bin/classes
COPY target/dependency /usrapp/bin/dependency

EXPOSE 35000

# En Linux el separador de classpath es ":", como en la guía.
# Usamos nuestro main actual: co.edu.escuelaing.reflexionlab.MicroSpringBoot
CMD ["java","-cp","./classes:./dependency/*","co.edu.escuelaing.reflexionlab.MicroSpringBoot"]
