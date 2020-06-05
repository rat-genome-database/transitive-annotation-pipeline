# transitive-annotation-pipeline

## New logic (as of June 2020)
Create transitive (ISO) annotations for all public species in RGD,
based on manual annotations as identified by evidence codes 'EXP', 'IDA', 'IPI', 'IMP', 'IGI', 'IEP', 'IAGP', 'HTP', 'HAD', 'HMP', 'HGI', 'HEP'
and based on orthologous genes.
With_info field of the manual annotation must be empty.

Notes:

1. annotations to CHEBI, HP and MP ontologies are *NOT* processed by the pipeline.
2. duplicate annotations are not created (if there is another annotation in RGD having the same RGD ID, term accession and qualifier as the transitive annotatation,
   then the transitive annotation is considered a duplicate and it won't be created).
3. incoming similar manual annotations are merged
  - if there are 2+ incoming annotations that are the same except XREF_SOURCE field,
     then they are merged into one (by merging their XREF_SOURCE fields)
      (note: such merged annots must be split if length of XREF_SRC is > 4000 due to Oracle limitations)


## Old logic (until June 2020)
Create transitive (ISS) annotations for non-core species (bonobo, chinchilla, dog, pig, squirrel, vervet (green monkey) and naked mole rat) based on human gene annotations.

Notes:

1. human annotations to CHEBI, HP and MP ontologies are *NOT* processed by the pipeline.
2. for dog and pig, transitive GO annotations are *not* created; for dog and pig, GO annotations are loaded from EBI source via nonrat-GO-annotation-pipeline.
3. WITH_INFO field rules

  * if WITH_INFO for human gene is null, set it to 'RGD:<human-gene-rgd-id>'

  * if WITH_INFO for human gene is not null, and evidence code is one of {'ISS','ISO'}, set it to WITH_INFO from human annotation

  * if WITH_INFO for human gene is not null, and evidence code is other than {'ISS','ISO'}, set it to 'RGD:<human-gene-rgd-id>'
