# transitive-annotation-pipeline
Create transitive (ISS) annotations for non-core species (bonobo, chinchilla, dog, pig, squirrel, vervet (green monkey) and naked mole rat) based on human gene annotations.

Notes:

1. human annotations to CHEBI, HP and MP ontologies are *NOT* processed by the pipeline.

2. for dog and pig, transitive GO annotations are *not* created; for dog and pig, GO annotations are loaded from EBI source via nonrat-GO-annotation-pipeline.

3. WITH_INFO field rules

  3.1 if WITH_INFO for human gene is null, set it to 'RGD:<human-gene-rgd-id>'

  b. if WITH_INFO for human gene is not null, and evidence code is one of {'ISS','ISO'}, set it to WITH_INFO from human annotation

  c. if WITH_INFO for human gene is not null, and evidence code is other than {'ISS','ISO'}, set it to 'RGD:<human-gene-rgd-id>'
