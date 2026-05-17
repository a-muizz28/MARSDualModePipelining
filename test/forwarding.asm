.text
main:
    addiu $t0, $zero, 5      # $t0 = 5
    addiu $t1, $zero, 7      # $t1 = 7

    addu  $t2, $t0, $t1      # $t2 = 12
                              # Uses recent values from previous instructions

    addu  $t3, $t2, $t0      # $t3 = 17
                              # Data hazard: needs $t2 immediately
                              # Forwarding should avoid a stall here
