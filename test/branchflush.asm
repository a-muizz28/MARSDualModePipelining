.text
main:
    addiu $t0, $zero, 1      # $t0 = 1
    addiu $t1, $zero, 1      # $t1 = 1

    beq   $t0, $t1, target   # Branch is taken because $t0 == $t1

    addiu $t2, $zero, 99     # Wrong-path instruction
                              # This should be flushed if branch is taken

target:
    addiu $t2, $zero, 7      # Correct target instruction
                              # Final value should be $t2 = 7
