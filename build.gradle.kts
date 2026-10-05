plugins {
    // Load the convention plugins (and Spotless with them) once in the root class loader.
    // Otherwise sibling projects with different plugin sets get separate copies of Spotless,
    // and its shared build service fails with a class loader mismatch.
    id("leia.java-conventions") apply false
}
