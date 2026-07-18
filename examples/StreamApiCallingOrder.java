import java.util.stream.Stream;


public class StreamApiCallingOrder {}

//code 1
public static void main(String[] args) {
    Stream.of(1, 2, 3)
        .filter(n -> {
            System.out.println("filter: " + n);
            return n > 1;
        })
        .map(n -> {
            System.out.println("map: " + n);
            return n * 2;
        })
        .forEach(n -> System.out.println("forEach: " + n));
}
//output 1:
filter: 1
filter: 2
map: 2
forEach: 4
filter: 3
map: 3
forEach: 6


//code 2
public static void main(String[] args) {
    Stream.of(1, 2, 3, 4, 5)
        .filter(n -> {
            System.out.println("checking: " + n);
            return n % 2 == 0; // even number
        })
        .findFirst()
        .ifPresent(n -> System.out.println("found: " + n));
}
//output 2
checking: 1
checking: 2
found: 2