.text
main:
    addiu $sp, $sp, -4       # Move stack pointer down by 4 bytes

    addiu $t0, $zero, 55     # $t0 = 55

    addu  $t1, $t0, $t0      # $t1 = 110
                              # Produces value that will be stored next

    sw    $t1, 0($sp)        # Store $t1 to memory
                              # Store needs a recently produced value
                              # Forwarding may be used so store gets correct data
