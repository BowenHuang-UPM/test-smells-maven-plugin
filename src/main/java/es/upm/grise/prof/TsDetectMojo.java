package es.upm.grise.prof;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Mojo(name = "detect", defaultPhase = LifecyclePhase.TEST)
public class TsDetectMojo extends AbstractMojo {
    
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;
    
    @Parameter(property = "tsDetectJar", defaultValue = "TestSmellDetector.jar")
    private String tsDetectJar;
    
    @Override
    public void execute() throws MojoExecutionException {
        try {
            System.out.println("Iniciando deteccion de malos olores en pruebas...");
            
            // Buscar el archivo JAR
            File jarFile = buscarJar();
            System.out.println("JAR encontrado: " + jarFile.getName());
            
            // Obtener archivos de prueba
            Map<File, File> archivosPrueba = obtenerArchivosPrueba();
            
            if (archivosPrueba.isEmpty()) {
                System.out.println("No se encontraron archivos de prueba .java");
                return;
            }
            
            System.out.println("Archivos de prueba encontrados: " + archivosPrueba.size());
            
            // Crear CSV de entrada
            File inputCsv = crearCSVEntrada(jarFile.getParentFile(), archivosPrueba);
            System.out.println("CSV generado: " + inputCsv.getName());
            
            // Ejecutar TestSmellDetector
            System.out.println("Ejecutando TestSmellDetector...");
            ejecutarTestSmellDetector(jarFile, inputCsv);
            
            // Buscar resultados
            File outputCsv = buscarArchivoResultados(jarFile.getParentFile(), inputCsv);
            
            if (outputCsv != null && outputCsv.exists()) {
                // Copiar al directorio target
                File targetDir = new File(project.getBuild().getDirectory());
                targetDir.mkdirs();
                File targetOutput = new File(targetDir, "test-smells-results.csv");
                Files.copy(outputCsv.toPath(), targetOutput.toPath(), 
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                // Mostrar resultados
                mostrarResultados(targetOutput);
                
                // Limpiar archivo temporal
                inputCsv.delete();
            } else {
                System.out.println("No se generaron resultados (posiblemente no hay malos olores)");
            }
            
            System.out.println("Proceso completado");
            
        } catch (Exception e) {
            throw new MojoExecutionException("Error: " + e.getMessage(), e);
        }
    }
    
    private File buscarJar() throws FileNotFoundException {
        File[] ubicaciones = {
            new File(tsDetectJar),
            new File(project.getBasedir(), tsDetectJar),
            new File(project.getBasedir(), "lib/" + tsDetectJar),
            new File("TestSmellDetector.jar")
        };
        
        for (File ubicacion : ubicaciones) {
            if (ubicacion.exists()) {
                return ubicacion;
            }
        }
        
        throw new FileNotFoundException("Archivo TestSmellDetector.jar no encontrado");
    }
    
    private Map<File, File> obtenerArchivosPrueba() throws IOException {
        Map<File, File> mapeo = new HashMap<>();
        
        Path dirPruebas = Paths.get(project.getBuild().getTestSourceDirectory());
        Path dirPrincipal = Paths.get(project.getBuild().getSourceDirectory());
        
        if (!Files.exists(dirPruebas)) {
            return mapeo;
        }
        
        List<File> archivosPrueba = new ArrayList<>();
        buscarArchivosJava(dirPruebas.toFile(), archivosPrueba);
        
        for (File archivoPrueba : archivosPrueba) {
            File archivoPrincipal = buscarArchivoPrincipal(archivoPrueba, dirPrincipal.toFile());
            mapeo.put(archivoPrueba, archivoPrincipal);
        }
        
        return mapeo;
    }
    
    private void buscarArchivosJava(File directorio, List<File> resultados) {
        File[] archivos = directorio.listFiles();
        if (archivos == null) return;
        
        for (File archivo : archivos) {
            if (archivo.isDirectory()) {
                buscarArchivosJava(archivo, resultados);
            } else if (archivo.getName().endsWith(".java")) {
                resultados.add(archivo);
            }
        }
    }
    
    private File buscarArchivoPrincipal(File archivoPrueba, File dirPrincipal) {
        if (archivoPrueba == null || dirPrincipal == null || !dirPrincipal.exists()) {
            return null;
        }
        
        String nombrePrueba = archivoPrueba.getName();
        String nombreBase = nombrePrueba;
        
        String[] sufijos = {"Test.java", "Tests.java", "TestCase.java", "IT.java"};
        for (String sufijo : sufijos) {
            if (nombrePrueba.endsWith(sufijo)) {
                nombreBase = nombrePrueba.substring(0, nombrePrueba.length() - sufijo.length()) + ".java";
                break;
            }
        }
        
        if (nombreBase.equals(nombrePrueba)) {
            nombreBase = nombrePrueba.replace("Test", ".java");
        }
        
        return buscarEnDirectorio(nombreBase, dirPrincipal);
    }
    
    private File buscarEnDirectorio(String nombreArchivo, File directorio) {
        if (!directorio.exists()) return null;
        
        File[] archivos = directorio.listFiles();
        if (archivos == null) return null;
        
        for (File archivo : archivos) {
            if (archivo.isDirectory()) {
                File encontrado = buscarEnDirectorio(nombreArchivo, archivo);
                if (encontrado != null) return encontrado;
            } else if (archivo.getName().equalsIgnoreCase(nombreArchivo)) {
                return archivo;
            }
        }
        
        return null;
    }
    
    private File crearCSVEntrada(File directorio, Map<File, File> archivosPrueba) throws IOException {
        File csvFile = new File(directorio, "input.csv");
        
        try (PrintWriter writer = new PrintWriter(csvFile, "UTF-8")) {
            for (Map.Entry<File, File> entry : archivosPrueba.entrySet()) {
                if (entry.getValue() != null) {
                    writer.printf("%s,%s%n", 
                        entry.getKey().getAbsolutePath(),
                        entry.getValue().getAbsolutePath());
                } else {
                    writer.printf("%s,%n", entry.getKey().getAbsolutePath());
                }
            }
        }
        
        return csvFile;
    }
    
    private void ejecutarTestSmellDetector(File jarFile, File inputCsv) 
            throws IOException, InterruptedException {
        
        String[] comando = {
            "java",
            "-jar",
            jarFile.getAbsolutePath(),
            inputCsv.getAbsolutePath()
        };
        
        ProcessBuilder pb = new ProcessBuilder(comando);
        pb.directory(jarFile.getParentFile());
        
        Process proceso = pb.start();
        
        // Silenciar salida normal, solo mostrar errores
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proceso.getInputStream()))) {
            String linea;
            while ((linea = reader.readLine()) != null) {
                // Solo mostrar si hay error
                if (linea.toLowerCase().contains("error") || linea.toLowerCase().contains("exception")) {
                    System.out.println("TestSmellDetector: " + linea);
                }
            }
        }
        
        int exitCode = proceso.waitFor();
        
        if (exitCode != 0) {
            throw new IOException("TestSmellDetector fallo con codigo: " + exitCode);
        }
    }
    
    private File buscarArchivoResultados(File directorio, File inputCsv) {
        File[] archivos = directorio.listFiles((dir, name) -> 
            name.toLowerCase().endsWith(".csv") && 
            !name.equals(inputCsv.getName())
        );
        
        if (archivos != null && archivos.length > 0) {
            // Devolver el mas reciente
            Arrays.sort(archivos, (f1, f2) -> 
                Long.compare(f2.lastModified(), f1.lastModified()));
            return archivos[0];
        }
        
        return null;
    }
    
    private void mostrarResultados(File outputCsv) throws IOException {
        if (!outputCsv.exists()) {
            System.out.println("Archivo de resultados no disponible");
            return;
        }
        
        List<String> lineas = Files.readAllLines(outputCsv.toPath());
        
        if (lineas.isEmpty()) {
            System.out.println("No se detectaron malos olores");
            return;
        }
        
        // Determinar si hay encabezados
        boolean tieneEncabezados = false;
        String primeraLinea = lineas.get(0);
        
        if (primeraLinea.contains("TestFilePath") || 
            primeraLinea.contains("testFilePath") ||
            primeraLinea.contains("appName")) {
            tieneEncabezados = true;
        }
        
        int inicioDatos = tieneEncabezados ? 1 : 0;
        int archivosConProblemas = 0;
        int totalProblemas = 0;
        
        System.out.println("\nResultados de la deteccion:");
        
        for (int i = inicioDatos; i < lineas.size(); i++) {
            String[] valores = lineas.get(i).split(",");
            if (valores.length > 0) {
                String nombreArchivo = "Desconocido";
                if (!valores[0].isEmpty()) {
                    try {
                        nombreArchivo = new File(valores[0]).getName();
                    } catch (Exception e) {
                        nombreArchivo = valores[0];
                    }
                }
                
                // Contar problemas (valores "true")
                int problemasArchivo = 0;
                List<String> problemas = new ArrayList<>();
                
                for (int j = 1; j < valores.length; j++) {
                    if ("true".equalsIgnoreCase(valores[j].trim())) {
                        problemasArchivo++;
                        totalProblemas++;
                        
                        // Si hay encabezados, obtener nombre del problema
                        if (tieneEncabezados && j < lineas.get(0).split(",").length) {
                            String[] encabezados = lineas.get(0).split(",");
                            problemas.add(encabezados[j]);
                        }
                    }
                }
                
                if (problemasArchivo > 0) {
                    archivosConProblemas++;
                    System.out.println(nombreArchivo + " - Problemas: " + problemasArchivo);
                    if (!problemas.isEmpty()) {
                        for (String problema : problemas) {
                            System.out.println("  * " + problema);
                        }
                    }
                } else {
                    System.out.println(nombreArchivo + " - OK");
                }
            }
        }
        
        System.out.println("\nResumen:");
        System.out.println("Archivos analizados: " + (lineas.size() - inicioDatos));
        System.out.println("Archivos con problemas: " + archivosConProblemas);
        System.out.println("Total de problemas: " + totalProblemas);
    }
}